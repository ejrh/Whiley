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

package wyjc.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public abstract class WyFunctionOrMethod {
	private Method method;
	private Object[] bindings;
	
	public WyFunctionOrMethod(Method method, Object... bindings) {
		this.method = method;
		this.bindings = bindings;
	}
	
	public Object call(Object[] parameters) throws InvocationTargetException,
			IllegalAccessException {
		if (bindings != null) {
			Object[] copyOfBindings = bindings.clone();
			for (int i = 0, j = 0; i != bindings.length; ++i) {
				if (bindings[i] == null) {
					copyOfBindings[i] = parameters[j++];
				}
			}
			parameters = copyOfBindings;
		}

		return method.invoke(null, parameters);
	}	
	
	protected static Method find(String clazz, String name) {
		try {
			Class cl = Class.forName(clazz);
			for (Method m : cl.getDeclaredMethods()) {
				if (m.getName().equals(name)) {
					return m;
				}
			}
			throw new RuntimeException("Method Not Found: " + clazz + ":"
					+ name);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException("Class Not Found: " + clazz);
		}
	}
}
