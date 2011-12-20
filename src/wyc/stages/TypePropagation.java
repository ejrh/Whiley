// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyc.stages;

import static wyil.util.SyntaxError.*;
import static wyil.util.ErrorMessages.*;

import java.util.*;

import wyc.NameResolver;
import wyc.TypeExpander;
import wyc.lang.*;
import wyc.lang.WhileyFile.*;
import wyc.util.RefCountedHashMap;
import wyil.ModuleLoader;
import wyil.lang.Attribute;
import wyil.lang.Import;
import wyil.lang.ModuleID;
import wyil.lang.NameID;
import wyil.lang.PkgID;
import wyil.lang.Type;
import wyil.lang.Code.OpDir;
import wyil.util.Pair;
import wyil.util.ResolveError;
import wyil.util.SyntacticElement;
import wyil.util.SyntaxError;
import static wyil.util.SyntaxError.*;

/**
 * Propagates type information in a flow-sensitive fashion from declared
 * parameter and return types through assigned expressions, to determine types
 * for all intermediate expressions and variables. For example:
 * 
 * <pre>
 * int sum([int] data):
 *     r = 0          // infers int type for r, based on type of constant
 *     for v in data: // infers int type for v, based on type of data
 *         r = r + v  // infers int type for r, based on type of operands 
 *     return r       // infers int type for r, based on type of r after loop
 * </pre>
 * 
 * The flash points here are the variables <code>r</code> and <code>v</code> as
 * <i>they do not have declared types</i>. Type propagation is responsible for
 * determing their type.
 * 
 * Loops present an interesting challenge for type propagation. Consider this
 * example:
 * 
 * <pre>
 * real loopy(int max):
 *     i = 0
 *     while i < max:
 *         i = i + 0.5
 *     return i
 * </pre>
 * 
 * On the first pass through the loop, variable <code>i</code> is inferred to
 * have type <code>int</code> (based on the type of the constant <code>0</code>
 * ). However, the add expression is inferred to have type <code>real</code>
 * (based on the type of the rhs) and, hence, the resulting type inferred for
 * <code>i</code> is <code>real</code>. At this point, the loop must be
 * reconsidered taking into account this updated type for <code>i</code>.
 * 
 * In some cases, this process must update the underlying expressions to reflect
 * the correct operator. For example:
 * 
 * <pre>
 * {int} f({int} x, {int} y):
 *    return x+y
 * </pre>
 * 
 * Initially, the expression <code>x+y</code> is assumed to be arithmetic
 * addition. During type propagation, however, it becomes apparent that its
 * operands are both sets. Therefore, the underlying AST node is updated to
 * represent a set union.
 * 
 * <h3>References</h3>
 * <ul>
 * <li>
 * <p>
 * David J. Pearce and James Noble. Structural and Flow-Sensitive Types for
 * Whiley. Technical Report, Victoria University of Wellington, 2010.
 * </p>
 * </li>
 * </ul>
 * 
 * @author David J. Pearce
 * 
 */
public final class TypePropagation {
	private final ModuleLoader loader;
	private final NameResolver resolver;
	private final TypeExpander expander;
	private ArrayList<Scope> scopes = new ArrayList<Scope>();
	private String filename;
	private WhileyFile.FunDecl method;
	
	public TypePropagation(ModuleLoader loader, NameResolver resolver, TypeExpander expander) {
		this.loader = loader;
		this.expander = expander;
		this.resolver = resolver;
	}
	
	public void propagate(WhileyFile wf) {
		this.filename = wf.filename;
		
		ModuleID mid = wf.module;
		ArrayList<Import> imports = new ArrayList<Import>();
		imports.add(new Import(mid.pkg(), mid.module(), "*")); 
		imports.add(new Import(mid.pkg(), "*")); 
		
		for(WhileyFile.Decl decl : wf.declarations) {
			try {
				if (decl instanceof ImportDecl) {
					ImportDecl impd = (ImportDecl) decl;
					imports.add(1, new Import(new PkgID(impd.pkg), impd.module,
							impd.name));
				} else if(decl instanceof FunDecl) {
					propagate((FunDecl)decl,imports);
				} else if(decl instanceof TypeDecl) {
					propagate((TypeDecl)decl,imports);					
				} else if(decl instanceof ConstDecl) {
					propagate((ConstDecl)decl,imports);					
				}			
			} catch(SyntaxError e) {
				throw e;
			} catch(Throwable t) {
				internalFailure(t.getMessage(),filename,decl,t);
			}
		}
	}
	
	public void propagate(ConstDecl cd, ArrayList<Import> imports) {
		
	}
	
	public void propagate(TypeDecl td, ArrayList<Import> imports) throws ResolveError {		
		// first, expand the declared type
		Type nominalType = td.nominalType;
		Type rawType = expander.expand(nominalType);
		td.rawType = rawType;

		if(td.constraint != null) {						
			// second, construct the appropriate typing environment			
			RefCountedHashMap<String,Pair<Type,Type>> environment = new RefCountedHashMap<String,Pair<Type,Type>>();
			environment.put("$", new Pair<Type,Type>(nominalType,rawType));
			
			// FIXME: add names exposed from records and other types
			
			// third, propagate type information through the constraint 
			propagate(td.constraint,environment,imports);
		}
	}

	public void propagate(FunDecl fd, ArrayList<Import> imports) throws ResolveError {
		this.method = fd;
		RefCountedHashMap<String,Pair<Type,Type>> environment = new RefCountedHashMap<String,Pair<Type,Type>>();
		Type.Function type = fd.nominalType;		
		ArrayList<Type> paramTypes = type.params();
		
		int i=0;
		for (WhileyFile.Parameter p : fd.parameters) {						
			environment = environment.put(p.name,expand(paramTypes.get(i++)));
		}
		
		if(fd instanceof MethDecl) {
			MethDecl md = (MethDecl) fd;			
			Type.Method mt = (Type.Method) type; 
			environment = environment.put("this",expand(mt.receiver()));
		}
		
		if(fd.precondition != null) {
			propagate(fd.precondition,environment.clone(),imports);
		}
		
		if(fd.postcondition != null) {			
			environment = environment.put("$", expand(type.ret()));
			propagate(fd.postcondition,environment.clone(),imports);
			// The following is a little sneaky and helps to avoid unnecessary
			// copying of environments. 
			environment = environment.remove("$");
		}

		// Now, expand this functions type (but, only in the case that it hasn't
		// already been expanded during some other pass). This can happen, for
		// example, when typing a call to this function.
		if(fd.rawType == null) {
			fd.rawType = (Type.Function) expander.expand(fd.nominalType);			
		}
		
		propagate(fd.statements,environment,imports);
	}
	
	private RefCountedHashMap<String, Pair<Type, Type>> propagate(
			ArrayList<Stmt> body,
			RefCountedHashMap<String, Pair<Type, Type>> environment,
			ArrayList<Import> imports) {
		
		for (Stmt stmt : body) {
			environment = propagate(stmt, environment, imports);
		}
		
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt stmt,
			RefCountedHashMap<String, Pair<Type, Type>> environment,
			ArrayList<Import> imports) {
		
		// We have to clone the environment here to ensure that any updates made
		// within the statement-specific propagation rules do not interfere with
		// the environment(s) from the previous statements.
		environment = environment.clone();
		
		try {
			if(stmt instanceof Stmt.Assign) {
				return propagate((Stmt.Assign) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Return) {
				return propagate((Stmt.Return) stmt,environment,imports);
			} else if(stmt instanceof Stmt.IfElse) {
				return propagate((Stmt.IfElse) stmt,environment,imports);
			} else if(stmt instanceof Stmt.While) {
				return propagate((Stmt.While) stmt,environment,imports);
			} else if(stmt instanceof Stmt.For) {
				return propagate((Stmt.For) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Switch) {
				return propagate((Stmt.Switch) stmt,environment,imports);
			} else if(stmt instanceof Expr.Invoke) {
				propagate((Expr.Invoke) stmt,environment,imports);
				return environment;
			} else if(stmt instanceof Stmt.DoWhile) {
				return propagate((Stmt.DoWhile) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Break) {
				return propagate((Stmt.Break) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Throw) {
				return propagate((Stmt.Throw) stmt,environment,imports);
			} else if(stmt instanceof Stmt.TryCatch) {
				return propagate((Stmt.TryCatch) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Assert) {
				return propagate((Stmt.Assert) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Debug) {
				return propagate((Stmt.Debug) stmt,environment,imports);
			} else if(stmt instanceof Stmt.Skip) {
				return propagate((Stmt.Skip) stmt,environment,imports);
			} else {
				internalFailure("unknown statement encountered",filename,stmt);
				return null; // deadcode
			}
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,stmt,e);
			return null; // dead code
		}
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Assert stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		stmt.expr = propagate(stmt.expr,environment,imports);
		checkIsSubtype(Type.T_BOOL,stmt.expr);
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Assign stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		Expr lhs = propagate(stmt.lhs,environment,imports);
		Expr rhs = propagate(stmt.rhs,environment,imports);
		if(lhs instanceof Expr.LVal) {
			stmt.lhs = (Expr.LVal) lhs;
		} else {
			syntaxError(errorMessage(INVALID_LVAL_EXPRESSION), filename,
					stmt.lhs);
		}		
		stmt.rhs = rhs;

		// FIXME: update the type of the assigned lval. 
		
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Break stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		// nothing to do
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Debug stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		stmt.expr = propagate(stmt.expr,environment,imports);
		checkIsSubtype(Type.T_STRING,stmt.expr);
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.DoWhile stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		
		if (stmt.invariant != null) {
			stmt.invariant = propagate(stmt.invariant, environment, imports);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}
		
		// FIXME: need to iterate to a fixed point		
		environment = propagate(stmt.body,environment,imports);
		
		stmt.condition = propagate(stmt.condition,environment,imports);
		checkIsSubtype(Type.T_BOOL,stmt.condition);			
		
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.For stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		
		stmt.source = propagate(stmt.source,environment,imports);
		checkIsSubtype(Type.T_BOOL,stmt.source);			
		
		for(String var : stmt.variables) {
			if (environment.containsKey(var)) {
				syntaxError(errorMessage(VARIABLE_ALREADY_DEFINED,var),
						filename, stmt);
			}
			environment = environment.put(var, ?);
		} 
		
		if (stmt.invariant != null) {
			stmt.invariant = propagate(stmt.invariant, environment, imports);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}
		
		// FIXME: need to iterate to a fixed point
		environment = propagate(stmt.body,environment,imports);
		
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.IfElse stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return environment;
	}
	
	private RefCountedHashMap<String, Pair<Type, Type>> propagate(
			Stmt.Return stmt,
			RefCountedHashMap<String, Pair<Type, Type>> environment,
			ArrayList<Import> imports) throws ResolveError {
		if (stmt.expr != null) {
			stmt.expr = propagate(stmt.expr, environment,imports);
			Type lhs = method.nominalType.ret();
			Type rhs = stmt.expr.nominalType();
			Type lhsExpanded = expander.expand(lhs);
			Type rhsExpanded = expander.expand(rhs);
			checkIsSubtype(lhs,lhsExpanded,rhs,rhsExpanded, stmt.expr);
		}
		return BOTTOM;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Skip stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Switch stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.Throw stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.TryCatch stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return environment;
	}
	
	private RefCountedHashMap<String,Pair<Type,Type>> propagate(Stmt.While stmt,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {

		stmt.condition = propagate(stmt.condition,environment,imports);
		checkIsSubtype(Type.T_BOOL,stmt.condition);			
		
		if (stmt.invariant != null) {
			stmt.invariant = propagate(stmt.invariant, environment, imports);
			checkIsSubtype(Type.T_BOOL,stmt.invariant);
		}		
		
		// FIXME: need to iterate to a fixed point
		environment = propagate(stmt.body,environment,imports);
		
		return environment;
	}
	
	private Expr propagate(Expr expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		
		try {
			if(expr instanceof Expr.BinOp) {
				return propagate((Expr.BinOp) expr,environment,imports); 
			} else if(expr instanceof Expr.Comprehension) {
				return propagate((Expr.Comprehension) expr,environment,imports); 
			} else if(expr instanceof Expr.Constant) {
				return propagate((Expr.Constant) expr,environment,imports); 
			} else if(expr instanceof Expr.Convert) {
				return propagate((Expr.Convert) expr,environment,imports); 
			} else if(expr instanceof Expr.Dictionary) {
				return propagate((Expr.Dictionary) expr,environment,imports); 
			} else if(expr instanceof Expr.Function) {
				return propagate((Expr.Function) expr,environment,imports); 
			} else if(expr instanceof Expr.Invoke) {
				return propagate((Expr.Invoke) expr,environment,imports); 
			} else if(expr instanceof Expr.AbstractIndexAccess) {
				return propagate((Expr.AbstractIndexAccess) expr,environment,imports); 
			} else if(expr instanceof Expr.AbstractLength) {
				return propagate((Expr.AbstractLength) expr,environment,imports); 
			} else if(expr instanceof Expr.AbstractVariable) {
				return propagate((Expr.AbstractVariable) expr,environment,imports); 
			} else if(expr instanceof Expr.List) {
				return propagate((Expr.List) expr,environment,imports); 
			} else if(expr instanceof Expr.Set) {
				return propagate((Expr.Set) expr,environment,imports); 
			} else if(expr instanceof Expr.SubList) {
				return propagate((Expr.SubList) expr,environment,imports); 
			} else if(expr instanceof Expr.AbstractDotAccess) {
				return propagate((Expr.AbstractDotAccess) expr,environment,imports); 
			} else if(expr instanceof Expr.Record) {
				return propagate((Expr.Record) expr,environment,imports); 
			} else if(expr instanceof Expr.Spawn) {
				return propagate((Expr.Spawn) expr,environment,imports); 
			} else if(expr instanceof Expr.Tuple) {
				return  propagate((Expr.Tuple) expr,environment,imports); 
			} else if(expr instanceof Expr.TypeVal) {
				return propagate((Expr.TypeVal) expr,environment,imports); 
			} 
		} catch(SyntaxError e) {
			throw e;
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,expr,e);
			return null; // dead code
		}		
		internalFailure("unknown expression encountered (" + expr.getClass().getName() +")",filename,expr);
		return null; // dead code
	}
	
	private Expr propagate(Expr.BinOp expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) throws ResolveError {
		expr.lhs = propagate(expr.lhs,environment,imports);
		expr.rhs = propagate(expr.rhs,environment,imports);
		Type lhsExpanded = expr.lhs.rawType();
		Type rhsExpanded = expr.rhs.rawType();
	
		boolean lhs_set = Type.isSubtype(Type.Set(Type.T_ANY, false),lhsExpanded);
		boolean rhs_set = Type.isSubtype(Type.Set(Type.T_ANY, false),rhsExpanded);		
		boolean lhs_list = Type.isSubtype(Type.List(Type.T_ANY, false),lhsExpanded);
		boolean rhs_list = Type.isSubtype(Type.List(Type.T_ANY, false),rhsExpanded);
		boolean lhs_str = Type.isSubtype(Type.T_STRING,lhsExpanded);
		boolean rhs_str = Type.isSubtype(Type.T_STRING,rhsExpanded);
		
		Type result;
		if(lhs_str || rhs_str) {						
			if(expr.op == Expr.BOp.ADD) {								
					expr.op = Expr.BOp.STRINGAPPEND;				
			} else {
					syntaxError("Invalid string operation: " + expr.op,filename,expr);					
			}
			
			result = Type.T_STRING;
		} else if(lhs_set && rhs_set) {		
			Type.Set type = Type.effectiveSetType(Type.Union(lhsExpanded,rhsExpanded));
			
			switch(expr.op) {				
				case ADD:																				
					expr.op = Expr.BOp.UNION;
					break;
				case BITWISEAND:																				
					expr.op = Expr.BOp.INTERSECTION;
					break;
				case SUB:																				
					expr.op = Expr.BOp.DIFFERENCE;
					break;
				default:
					syntaxError("Invalid set operation: " + expr.op,filename,expr);		
			}
			
			result = type;
		} else if(lhs_list && rhs_list) {
			Type.List type = Type.effectiveListType(Type.Union(lhsExpanded,rhsExpanded));
			
			if(expr.op == Expr.BOp.ADD){ 																							
					expr.op = Expr.BOp.LISTAPPEND;
			} else {
					syntaxError("Invalid set operation: " + expr.op,filename,expr);		
			}
			
			result = type;			
		} else {			
			switch(expr.op) {
			case BITWISEAND:
			case BITWISEOR:
			case BITWISEXOR:
				checkIsSubtype(Type.T_BYTE,expr.lhs);
				checkIsSubtype(Type.T_BYTE,expr.rhs);
				result = Type.T_BYTE;
			case LEFTSHIFT:
			case RIGHTSHIFT:
				checkIsSubtype(Type.T_BYTE,expr.lhs);
				checkIsSubtype(Type.T_INT,expr.rhs);
				result = Type.T_BYTE;
			case RANGE:
				checkIsSubtype(Type.T_INT,expr.lhs);
				checkIsSubtype(Type.T_INT,expr.rhs);
				result = Type.List(Type.T_INT, false);
			case REM:
				checkIsSubtype(Type.T_INT,expr.lhs);
				checkIsSubtype(Type.T_INT,expr.rhs);
				result = Type.T_INT;
			default:
				// all other operations go through here
				if(Type.isImplicitCoerciveSubtype(lhsExpanded,rhsExpanded)) {
					checkIsSubtype(Type.T_REAL,expr.lhs);
					if(Type.isSubtype(Type.T_CHAR, lhsExpanded)) {
						result = Type.T_CHAR;
					} else if(Type.isSubtype(Type.T_INT, lhsExpanded)) {
						result = Type.T_INT;
					} else {
						result = Type.T_REAL;
					}				
				} else {
					checkIsSubtype(Type.T_REAL,expr.lhs);
					checkIsSubtype(Type.T_REAL,expr.rhs);				
					if(Type.isSubtype(Type.T_CHAR, rhsExpanded)) {
						result = Type.T_CHAR;
					} else if(Type.isSubtype(Type.T_INT, rhsExpanded)) {
						result = Type.T_INT;
					} else {
						result = Type.T_REAL;
					}
				} 			
			}
		}	
		
		/**
		 * Finally, save the resulting types for this expression.
		 */
		
		expr.nominalType = result;
		expr.rawType = result;
		
		return expr;
	}
	
	private Expr propagate(Expr.Comprehension expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return expr;
	}
	
	private Expr propagate(Expr.Constant expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return expr;
	}

	private Expr propagate(Expr.Convert c,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) throws ResolveError {
		c.expr = propagate(c.expr,environment,imports);
		Type from = c.expr.rawType();
		Type to = expander.expand(c.nominalType);
		if (!Type.isExplicitCoerciveSubtype(to, from)) {			
			syntaxError(errorMessage(SUBTYPE_ERROR, to, from), filename, c);
		}	
		c.rawType = to;
		return c;
	}
	
	private Expr propagate(Expr.Dictionary expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.Function expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.Invoke expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}	
	
	private Expr propagate(Expr.AbstractIndexAccess expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) throws ResolveError {			
		expr.src = propagate(expr.src,environment,imports);
		expr.index = propagate(expr.index,environment,imports);		
		Type src = expr.src.nominalType();		
		Type srcExpanded = expander.expand(src);
				
		// First, check whether this is still only an abstract access and, in
		// such case, upgrade it to the appropriate access expression.
		
		if (!(expr instanceof Expr.StringAccess)
				&& Type.isImplicitCoerciveSubtype(Type.T_STRING, src)) {
			expr = new Expr.StringAccess(expr.src,expr.index,expr.attributes());
		} else if (!(expr instanceof Expr.ListAccess)
				&& Type.isImplicitCoerciveSubtype(Type.List(Type.T_ANY, false),
						src)) {
			expr = new Expr.ListAccess(expr.src,expr.index,expr.attributes());
		} else if (!(expr instanceof Expr.DictionaryAccess)) {
			expr = new Expr.DictionaryAccess(expr.src,expr.index,expr.attributes());
		}
		
		// Second, determine the expanded src type for this access expression
		// and check the key value.
		
		if(expr instanceof Expr.StringAccess) {
			checkIsSubtype(Type.T_STRING,expr.src);	
			checkIsSubtype(Type.T_INT,expr.index);				
		} else if(expr instanceof Expr.ListAccess) {
			Expr.ListAccess la = (Expr.ListAccess) expr; 
			Type.List list = Type.effectiveListType(src);			
			if(list == null) {
				syntaxError(errorMessage(INVALID_LIST_EXPRESSION),filename,expr);				
			}
			checkIsSubtype(Type.T_INT,expr.index);		
			la.rawSrcType = list;
			// FIXME: lost nominal information
			la.nominalElementType = list.element();
		} else {
			Expr.DictionaryAccess da = (Expr.DictionaryAccess) expr; 
			Type.Dictionary dict = Type.effectiveDictionaryType(srcExpanded);
			if(dict == null) {
				syntaxError(errorMessage(INVALID_DICTIONARY_EXPRESSION),filename,expr);
			}			
			checkIsSubtype(dict.key(),expr.index);						
			da.rawSrcType = dict;
			// FIXME: lost nominal information
			da.nominalElementType = dict.value();
		}
		
		return expr;
	}
	
	private Expr propagate(Expr.AbstractLength expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) throws ResolveError {			
		expr.src = propagate(expr.src,environment,imports);			
		Type src = expr.src.nominalType();		
		Type srcExpanded = expander.expand(src);
	
		// First, check whether this is still only an abstract access and, in
				// such case, upgrade it to the appropriate access expression.

		if (!(expr instanceof Expr.StringLength)
				&& Type.isImplicitCoerciveSubtype(Type.T_STRING, src)) {
			expr = new Expr.StringLength(expr.src,expr.attributes());
		} else if (!(expr instanceof Expr.ListLength)
				&& Type.isImplicitCoerciveSubtype(Type.List(Type.T_ANY, false),
						src)) {
			expr = new Expr.ListLength(expr.src,expr.attributes());
		} else if (!(expr instanceof Expr.SetLength)
				&& Type.isImplicitCoerciveSubtype(Type.Set(Type.T_ANY, false),
						src)) {
			expr = new Expr.SetLength(expr.src,expr.attributes());
		} else if (!(expr instanceof Expr.DictionaryLength)) {
			expr = new Expr.DictionaryLength(expr.src,expr.attributes());
		}

		// Second, determine the expanded src type for this access expression
		// and check the key value.

		if(expr instanceof Expr.StringLength) {
			checkIsSubtype(Type.T_STRING,expr.src);								
		} else if(expr instanceof Expr.ListLength) {
			Expr.ListLength ll = (Expr.ListLength) expr; 
			Type.List list = Type.effectiveListType(src);			
			if(list == null) {
				syntaxError(errorMessage(INVALID_LIST_EXPRESSION),filename,expr);				
			}
			ll.rawSrcType = list;
		} else if(expr instanceof Expr.SetLength) {
			Expr.SetLength sl = (Expr.SetLength) expr; 
			Type.Set list = Type.effectiveSetType(src);			
			if(list == null) {
				syntaxError(errorMessage(INVALID_SET_EXPRESSION),filename,expr);				
			}
			sl.rawSrcType = list;
		} else {
			Expr.DictionaryLength da = (Expr.DictionaryLength) expr; 
			Type.Dictionary dict = Type.effectiveDictionaryType(srcExpanded);
			if(dict == null) {
				syntaxError(errorMessage(INVALID_DICTIONARY_EXPRESSION),filename,expr);
			}						
			da.rawSrcType = dict;
		}
		
		return expr;
	}
	
	private Expr propagate(Expr.AbstractVariable expr,
			RefCountedHashMap<String, Pair<Type, Type>> environment,
			ArrayList<Import> imports) throws ResolveError {

		Pair<Type, Type> types = environment.get(expr.var);

		if (expr instanceof Expr.LocalVariable) {
			Expr.LocalVariable lv = (Expr.LocalVariable) expr;
			lv.nominalType = types.first();
			lv.rawType = types.second();
			return lv;
		} else if (types != null) {
			// yes, this is a local variable
			Expr.LocalVariable lv = new Expr.LocalVariable(expr.var,
					expr.attributes());			
			lv.nominalType = types.first();
			lv.rawType = types.second();
			return lv;
		} else {
			// This variable access may correspond to an external access.
			// Therefore, we must determine which module this
			// is, and update the tree accordingly.
			try {
				NameID nid = resolver.resolveAsName(expr.var, imports);
				return new Expr.ConstantAccess(null, expr.var, nid,
						expr.attributes());
			} catch (ResolveError err) {
			}
			// In this case, we may still be OK if this corresponds to an
			// explicit module or package access.
			try {
				ModuleID mid = resolver.resolveAsModule(expr.var, imports);
				return new Expr.ModuleAccess(null, expr.var, mid,
						expr.attributes());
			} catch (ResolveError err) {
			}
			PkgID pid = new PkgID(expr.var);
			if (resolver.isPackage(pid)) {
				return new Expr.PackageAccess(null, expr.var, pid,
						expr.attributes());
			}
			// ok, failed.
			syntaxError(errorMessage(UNKNOWN_VARIABLE), filename, expr);
			return null; // deadcode
		}
	}
	
	private Expr propagate(Expr.Set expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.List expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.SubList expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.AbstractDotAccess expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.RecordAccess expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.ConstantAccess expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}		
	
	private Expr propagate(Expr.Record expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}

	private Expr propagate(Expr.Spawn expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}

	private Expr propagate(Expr.Tuple expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private Expr propagate(Expr.TypeVal expr,
			RefCountedHashMap<String,Pair<Type,Type>> environment,
			ArrayList<Import> imports) {
		return null;
	}
	
	private <T extends Type> T checkType(Type t, Class<T> clazz,
			SyntacticElement elem) {
		if (clazz.isInstance(t)) {
			return (T) t;
		} else {
			syntaxError(errorMessage(SUBTYPE_ERROR, clazz.getName(), t),
					filename, elem);
			return null;
		}
	}
	
	// Check t1 :> t2
	private void checkIsSubtype(Type t1, Type t1Expanded, Type t2,
			Type t2Expanded, SyntacticElement elem) {
		if (!Type.isImplicitCoerciveSubtype(t1Expanded, t2Expanded)) {			
			syntaxError(errorMessage(SUBTYPE_ERROR, t1, t2), filename, elem);
		}
	}		
	
	private void checkIsSubtype(Type t1, Expr t2) {		
		if (!Type.isImplicitCoerciveSubtype(t1, t2.rawType())) {
			// We use the nominal type for error reporting, since this includes
			// more helpful names.
			syntaxError(errorMessage(SUBTYPE_ERROR, t1, t2.nominalType()), filename, t2);
		}
	}
	
	/**
	 * The purpose of the exposed names method is capture the case when we have
	 * a define statement like this:
	 * 
	 * <pre>
	 * define tup as {int x, int y} where x < y
	 * </pre>
	 * 
	 * In this case, <code>x</code> and <code>y</code> are "exposed" --- meaning
	 * their real names are different in some way. In this case, the aliases we
	 * have are: x->$.x and y->$.y
	 * 
	 * @param src
	 * @param t
	 * @param environment
	 */
	private static void addExposedNames(Expr src, UnresolvedType t,
			HashMap<String, Set<Expr>> environment) {
		// Extended this method to handle lists and sets etc, is very difficult.
		// The primary problem is that we need to expand expressions involved
		// names exposed in this way into quantified
		// expressions.		
		if(t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			for(Map.Entry<String,UnresolvedType> e : tt.types.entrySet()) {
				Expr s = new Expr.RecordAccess(src, e
						.getKey(), src.attribute(Attribute.Source.class));
				addExposedNames(s,e.getValue(),environment);
				Set<Expr> aliases = environment.get(e.getKey());
				if(aliases == null) {
					aliases = new HashSet<Expr>();
					environment.put(e.getKey(),aliases);
				}
				aliases.add(s);
			}
		} else if (t instanceof UnresolvedType.Process) {			
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			addExposedNames(new Expr.ProcessAccess(src),
					ut.element, environment);
		}
	}
	
	private abstract static class Scope {
		public abstract void free();
	}
	
	private static final class Handler {
		public final Type exception;
		public final String variable;
		public RefCountedHashMap<String,Pair<Type,Type>> environment;
		
		public Handler(Type exception, String variable) {
			this.exception = exception;
			this.variable = variable;
			this.environment = new RefCountedHashMap<String,Pair<Type,Type>>();
		}
	}
	
	private static final class TryCatchScope extends Scope {
		public final ArrayList<Handler> handlers = new ArrayList<Handler>();
						
		public void free() {
			for(Handler handler : handlers) {
				handler.environment.free();
			}
		}
	}
	
	private static final class BreakScope extends Scope {
		public RefCountedHashMap<String,Pair<Type,Type>> environment;
		
		public void free() {
			environment.free();
		}
	}

	private static final class ContinueScope extends Scope {
		public RefCountedHashMap<String,Pair<Type,Type>> environment;
		
		public void free() {
			environment.free();
		}
	}
	
	private static final RefCountedHashMap<String,Pair<Type,Type>> BOTTOM = new RefCountedHashMap<String,Pair<Type,Type>>();
	
	private Pair<Type,Type> expand(Type nominalType) throws ResolveError {
		return new Pair<Type,Type>(nominalType,expander.expand(nominalType));
	}
}
