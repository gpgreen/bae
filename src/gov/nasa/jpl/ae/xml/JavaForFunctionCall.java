package gov.nasa.jpl.ae.xml;

import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Utils;
import gov.nasa.jpl.ae.xml.EventXmlToJava.Param;
import gov.nasa.jpl.ae.event.TimeVarying; // don't remove!!
import gov.nasa.jpl.ae.event.TimeVaryingMap; // don't remove!!
import japa.parser.ast.body.ConstructorDeclaration;
import japa.parser.ast.body.FieldDeclaration;
import japa.parser.ast.body.MethodDeclaration;
import japa.parser.ast.body.ModifierSet;
import japa.parser.ast.body.Parameter;
import japa.parser.ast.expr.Expression;
import japa.parser.ast.expr.MethodCallExpr;
import japa.parser.ast.expr.ObjectCreationExpr;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import jline.ArgumentCompletor;

public class JavaForFunctionCall {
  /**
   * 
   */
  private final EventXmlToJava xmlToJava;
  public MethodCallExpr methodCallExpr = null;
  public ObjectCreationExpr objectCreationExpr = null;
  public boolean methodOrConstructor = true; 
  public String object = null;
  public String className = null;
  public String callName = null;
  public Constructor< ? > matchingConstructor = null;
  public ConstructorDeclaration constructorDecl = null;
  public Method matchingMethod = null;
  public MethodDeclaration methodDecl = null;
  public String pkg = null;
  public String methodJava = null;  // Java text for getting java.reflect.Method
  public String argumentArrayJava = null;
  public Vector<String> args = null;
  private boolean convertingArgumentsToExpressions = false;
  public boolean evaluateCall = false;
  
  /**
   * When expressions are passed to functions that are expecting parameters, a
   * dependency can be formed.
   */
  public ArrayList<FieldDeclaration> generatedDependencies =
      new ArrayList< FieldDeclaration >();
  
  public JavaForFunctionCall( EventXmlToJava eventXmlToJava,
                              Expression expression,
                              boolean convertArgumentsToExpressions,
                              String preferredPackageName ) {
    this( eventXmlToJava, expression, convertArgumentsToExpressions,
          preferredPackageName, false );
  }
                              
  public JavaForFunctionCall( EventXmlToJava eventXmlToJava,
                              Expression expression,
                              boolean convertArgumentsToExpressions,
                              String preferredPackageName,
                              boolean evaluateCall ) {
    // Arguments may be Expressions, Parameters, or other. Method parameter
    // types may also be Expressions, Parameters, or other.
    //
    // If argument is Expression, and parameter is Parameter,
    //   see if the expression is a parameter and pass that instead.    
    //
    // If argument is Expression, and parameter is other,
    //   pass the evaluation of the Expression.
    //
    // If argument is Parameter, and parameter is Expression,
    //   wrap the argument in an Expression.
    //
    // If argument is Parameter, and parameter is other,
    //   pass the Parameter's value.
    //
    // If argument is other,
    //   wrap the argument in an Expression or Parameter according to the type.
    //
    // If there is a choice of methods, prefer matched types to matches through
    // conversion, except when convertArgumentsToExpressions==true. Prefer those
    // that match Expressions to those that match Parameters and Parameters to
    // other.

    assert expression != null;
    
    if ( expression instanceof MethodCallExpr ) {
      methodCallExpr = (MethodCallExpr)expression;
      methodOrConstructor = true;
    } else if ( expression instanceof ObjectCreationExpr ) {
      objectCreationExpr = (ObjectCreationExpr)expression;
      methodOrConstructor = false;
    } else {
      assert false;
    }
    
    this.xmlToJava = eventXmlToJava;
    // REVIEW -- How do we know when we want to convert args to Expressions?
    // Constructors of events (and probably classes) convert args to
    // Expressions.  For now, do not convert args for any other calls.
    this.convertingArgumentsToExpressions = convertArgumentsToExpressions;
    this.evaluateCall = evaluateCall;
    callName =
        methodOrConstructor ? methodCallExpr.getName()
                            : objectCreationExpr.getType().toString();
    className = this.xmlToJava.currentClass;
    
    // Get object from scope
    Expression scope = getScope();
    object = this.xmlToJava.getObjectFromScope( scope );
    String objectType = this.xmlToJava.astToAeExprType( scope, true, true );
    if ( objectType != null ) {
      className = objectType;
    }
    pkg = this.xmlToJava.packageName + ".";
    if ( pkg.length() == 1 ) {
      pkg = "";
    }
    
    if ( Utils.isNullOrEmpty( object ) ) {
      if ( methodOrConstructor ||
          xmlToJava.isInnerClass( objectType ) ) {
        object = "this";
      } else {
        object = "null";
      }
    }

    // Assemble Java text for finding the java.reflect.Method for callName

    StringBuffer methodJavaSb = new StringBuffer();
    Class< ? >[] argTypesArr = null;
    List<Expression> args = null;
    if ( methodOrConstructor ) {
      args = methodCallExpr.getArgs();
    } else {
      args = objectCreationExpr.getArgs();      
    }
    if ( args != null ) {
      argTypesArr = new Class< ? >[ args.size() ];
      for ( int i = 0; i < args.size(); ++i ) {
        argTypesArr[ i ] =
            Utils.getClassForName( xmlToJava.astToAeExprType( args.get( i ),
                                                              true, true ),
                                                              preferredPackageName,
                                                              false );
      }
    }
    if ( methodOrConstructor ) {
      methodJavaSb.append( "Utils.getMethodForArgTypes(\"" + className
                           + "\", \"" + preferredPackageName + "\", \""
                           + callName + "\"" );

      // Get the list of methods with the same name (callName).
      Set< MethodDeclaration > classMethods =
          this.xmlToJava.getClassMethodsWithName( callName, className );
      // Find the right MethodDeclaration if it exists.
      if ( !Utils.isNullOrEmpty( classMethods ) ) {

        methodDecl  = null;
        methodDecl =
            getBestArgTypes( classMethods, argTypesArr, preferredPackageName );
        if ( methodDecl == null ) {
          // Warning just grabs the first method of this name!
          if ( classMethods.size() > 1 ) {
            System.err.println( "Warning! " + classMethods.size()
                                + " methods with name " + callName + " in "
                                + className + ": just grabbing the first!" );
          }
          // Add vector of argument types to getMethod() call
          methodDecl = classMethods.iterator().next();
        }
        assert ( methodDecl != null );
        for ( japa.parser.ast.body.Parameter parameter : methodDecl.getParameters() ) {
          methodJavaSb.append( ", " );
          methodJavaSb.append( Utils.noParameterName( parameter.getType().toString() )
                               + ".class" );
        }
      } else { // if ( !classMethods.isEmpty() ) {
        matchingMethod  = null;
        // Try using reflection to find the method, but class may not exist.
        matchingMethod =
            Utils.getMethodForArgTypes( className, preferredPackageName,
                                        callName, argTypesArr );
        if ( matchingMethod != null && matchingMethod.getParameterTypes() != null ) {
          for ( Class< ? > type : matchingMethod.getParameterTypes() ) {
            methodJavaSb.append( ", " );
            String typeName = type.getName();
            if ( typeName != null ) typeName = typeName.replace( '$', '.' );
            methodJavaSb.append( Utils.noParameterName( typeName )
                                 + ".class" );
          }
        }
      }
    } else {
      methodJavaSb.append( "Utils.getConstructorForArgTypes(" 
                           + Utils.noParameterName( callName )
                           + ".class" );
      // Find the right MethodDeclaration if it exists.
      Set< ConstructorDeclaration > ctors =
          xmlToJava.getConstructors( callName );
      constructorDecl  = null;
      if ( !Utils.isNullOrEmpty( ctors ) ) {
        constructorDecl =
            getBestArgTypes( ctors, argTypesArr, preferredPackageName );
        if ( constructorDecl == null ) {
          constructorDecl = ctors.iterator().next();
          // Warning just grabs the first constructor!
          if ( ctors.size() > 1 ) {
            System.err.println( "Warning! " + ctors.size()
                                + " constructors for " + callName
                                + ": just grabbing the first!" );
          }
        }
        assert ( constructorDecl != null );
        if ( constructorDecl != null && constructorDecl.getParameters() != null ) {
          for ( japa.parser.ast.body.Parameter parameter : 
            constructorDecl.getParameters() ) {
            methodJavaSb.append( ", " );
            methodJavaSb.append( Utils.noParameterName( parameter.getType().toString() )
                                 + ".class" );
          }
        }
      }
      if ( constructorDecl == null ) {
        // Try using reflection to find the method, but class may not exist.
        matchingConstructor  =
            Utils.getConstructorForArgTypes( callName, argTypesArr,
                                             preferredPackageName );
        if ( matchingConstructor != null ) {
          for ( Class< ? > type : matchingConstructor.getParameterTypes() ) {
            methodJavaSb.append( ", " );
            String typeName = type.getName();
            if ( typeName != null ) typeName = typeName.replace( '$', '.' );
            methodJavaSb.append( Utils.noParameterName( typeName ) 
                                 + ".class" );
          }
        }
      }
    }
    methodJavaSb.append( " )" );
    methodJava = methodJavaSb.toString();
    
    // Build Java text to construct an array enclosing the arguments to be
    // passed to the method call.
    StringBuffer argumentArraySb = new StringBuffer();
    argumentArraySb.append( "new Object[]{ " );
    boolean first = true;
    if ( args != null ) {
      for ( Expression a : args ) {
        if ( first ) {
          first = false;
        } else {
          argumentArraySb.append( ", " );
        }
        if ( convertArgumentsToExpressions ) {
          String e = 
              xmlToJava.astToAeExpr( a, convertArgumentsToExpressions, true, true );
          if ( Utils.isNullOrEmpty( e ) || e.matches( "[(][^()]*[)]null" ) ) {
            argumentArraySb.append( a );
          } else {
            argumentArraySb.append( e );
          }
        } else {
          argumentArraySb.append( a );
        }
      }
    }
    argumentArraySb.append( " } " );
    argumentArrayJava = argumentArraySb.toString();
  }

  public <T> T getBestArgTypes( Set< T > declarations,
                                Class< ? >[] argTypesArr,
                                String preferredPackageName ) {
    Map< T, Pair< Class< ? >[], Boolean > > candidates =
        new HashMap< T, Pair< Class< ? >[], Boolean > >();
    for ( T cd : declarations ) {
      
      List< Parameter > params = null;
      if ( cd instanceof ConstructorDeclaration ) {
        params = ((ConstructorDeclaration)cd).getParameters();
      } else if ( cd instanceof MethodDeclaration ) {
        params = ((MethodDeclaration)cd).getParameters();
      }
      boolean gotParams = !Utils.isNullOrEmpty( params ); 
      int size = gotParams ? params.size() : 0;
      Class< ? >[] ctorArgTypes = new Class< ? >[ size ];
      int ct = 0;
      boolean isVarArgs = false;
      if ( gotParams ) {
        isVarArgs = params.get( size - 1 ).isVarArgs();
        for ( Parameter param : params ) {
          Class< ? > c =
              Utils.getClassForName( param.getType().toString(),
                                     preferredPackageName, true );
          ctorArgTypes[ ct++ ] = c;
        }
      }
      candidates.put( cd, new Pair< Class< ? >[], Boolean >( ctorArgTypes,
                                                             isVarArgs ) );
    }
    T decl = Utils.getBestArgTypes( candidates, argTypesArr );
    return decl;
  }

  public Expression getScope() {
    if ( methodCallExpr != null ) {
      return methodCallExpr.getScope();
    } else if ( objectCreationExpr != null ) {
      return objectCreationExpr.getScope();
    }
    return null;
  }

  /**
   * @return the convertArgumentsToExpressions
   */
  public boolean isConvertArgumentsToExpressions() {
    return convertingArgumentsToExpressions;
  }

  public boolean isStatic() {
    if ( methodOrConstructor ) {
      if ( xmlToJava.knowIfStatic( callName ) ) {
        return xmlToJava.isStatic( callName );
      }
      if ( matchingMethod != null &&
           Modifier.isStatic( matchingMethod.getModifiers() ) ) {
        return true;
      }
      if ( methodDecl != null &&
           ModifierSet.isStatic( methodDecl.getModifiers() ) ) {
        return true;
      }
    } else {
      if ( xmlToJava.knowIfClassIsStatic( callName ) ) {
        return xmlToJava.isClassStatic( callName );
      }
      if ( matchingConstructor != null &&
           Modifier.isStatic( matchingConstructor.getModifiers() ) ) {
        return true;
      }
      if ( constructorDecl != null &&
           ModifierSet.isStatic( constructorDecl.getModifiers() ) ) {
        return true;
      }
    }
    return Utils.isNullOrEmpty( object );
  }
  
  /**
   * @param convertArgumentsToExpressions the convertArgumentsToExpressions to set
   */
  public void
      setConvertArgumentsToExpressions( boolean convertArgumentsToExpressions ) {
    this.convertingArgumentsToExpressions = convertArgumentsToExpressions;
  }
  
  public String toNewFunctionCallString() {
    String fcs = null;
    if ( object.startsWith( "new FunctionCall" ) 
         || object.startsWith( "new ConstructorCall" ) ) {
      // nest the function calls
      fcs = "new " + ( methodCallExpr == null ? "Constructor" : "Function" )
            + "Call( null, " + methodJava + ", " + argumentArrayJava + ", "
            + object + " )";
    } else {
      String instance = object;
      if ( isStatic() ) {
        instance = "null";
      }
      fcs = "new " + ( methodCallExpr == null ? "Constructor" : "Function" )
            + "Call( " + instance + ", " + methodJava
            + ", " + argumentArrayJava + " )";
    }
    if ( evaluateCall && !Utils.isNullOrEmpty( fcs ) ) {
      if ( !convertingArgumentsToExpressions ) {
        fcs = "(" + fcs + ").evaluate(true)";
      }
    }
    return fcs;
  }
  public String toNewExpressionString() {
    return "new Expression( " + toNewFunctionCallString() + " )";
  }
  
  @Override
  public String toString() {
    return toNewFunctionCallString();
  }
  
}
//public Date epoch = new Date();