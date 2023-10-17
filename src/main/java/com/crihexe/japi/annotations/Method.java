package com.crihexe.japi.annotations;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(RUNTIME)
@Target(TYPE)
public @interface Method {
	
	public enum Auth {
		none,
		Bearer("Bearer"),
		Basic("Basic");
		
		public String name;
		
		Auth() {
			this("");
		}
		
		Auth(String name) {
			this.name = name;
		}
	}
	
	public enum Methods {
		GET,
		POST,
		PUT;
	}
	
	public Methods method() default Methods.GET;
	public Auth auth() default Auth.none;
	
}
