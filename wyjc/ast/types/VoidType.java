// This file is part of the Whiley-to-Java Compiler (wyjc).
//
// The Whiley-to-Java Compiler is free software; you can redistribute 
// it and/or modify it under the terms of the GNU General Public 
// License as published by the Free Software Foundation; either 
// version 3 of the License, or (at your option) any later version.
//
// The Whiley-to-Java Compiler is distributed in the hope that it 
// will be useful, but WITHOUT ANY WARRANTY; without even the 
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
// PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Whiley-to-Java Compiler. If not, see 
// <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyjc.ast.types;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import wyone.core.WType;
import wyone.theory.numeric.WIntType;

public final class VoidType extends ConstrainedType implements NonUnionType {
	public String toString() { return "void"; }
	
	VoidType() {}
	
	public boolean equals(Object o) {
		return o == Types.T_VOID;
	}
	
	public int hashCode() {
		return 0;
	}
		
	public Type lub(Type t) {
		return t;
	}
	
	public Type flattern() {
		return this;
	}
	
	public boolean isExistential() {
		return false;
	}
	
	public Type substitute(Map<String, Type> binding) {
		return this;
	}	
	
	public <T> Set<T> match(Class<T> type) {		
		if(VoidType.class == type) {
			HashSet r = new HashSet();
			r.add(this);
			return r;
		} 
		return Collections.EMPTY_SET;		
	}
	
	public WType convert() {
		return WIntType.T_INT;
	}
}
