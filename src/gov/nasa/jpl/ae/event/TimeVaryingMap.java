/**
 * 
 */
package gov.nasa.jpl.ae.event;

import gov.nasa.jpl.ae.solver.StringDomain;
import gov.nasa.jpl.ae.solver.Variable;
import gov.nasa.jpl.ae.util.Debug;
import gov.nasa.jpl.ae.util.Pair;
import gov.nasa.jpl.ae.util.Utils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import junit.framework.Assert;

/**
 * 
 * TimeVaryingMap is a {@link TreeMap} for implementing {@link TimeVarying},
 * mapping a {@link Timepoint} to {@link EffectInstance}s (although the map
 * value has a different encoding, {@link TimeValue}). It is also
 * implements {@link ParameterListener} in order to maintain {@link TreeMap}
 * consistency. It "floats" entries before its {@link Timepoint} changes to
 * protect the data structure and reinserts the entry after the
 * {@link Timepoint} has changed.
 * 
 * @author bclement
 * 
 */
public class TimeVaryingMap< T > extends TreeMap< Timepoint, T >
                                 implements TimeVarying< T >,
                                            ParameterListener {
                                            //Comparable< TimeVaryingMap< T > > {

  private static final long serialVersionUID = -2428504938515591538L;

  public class TimeValue extends Pair< Timepoint, T >
                               implements HasParameters {

    public TimeValue( Timepoint t, T v ) {
      super( t, v );
    }

    @Override
    public boolean isStale() {
      return HasParameters.Helper.isStale( this, false, null );
    }

    @Override
    public void setStale( boolean staleness ) {
      Assert.assertTrue( "This method is not supported!", false );
    }

    @Override
    public Set< Parameter< ? > > getParameters( boolean deep,
                                                Set< HasParameters > seen ) {
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return Utils.getEmptySet();
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
      return HasParameters.Helper.getParameters( this, deep, null );
    }

    @Override
    public Set< Parameter< ? > > getFreeParameters( boolean deep,
                                                    Set< HasParameters > seen ) {
      Assert.assertTrue( "This method is not supported!", false );
      return null;
    }

    @Override
    public void setFreeParameters( Set< Parameter< ? > > freeParams,
                                   boolean deep,
                                   Set< HasParameters > seen ) {
      Assert.assertTrue( "This method is not supported!", false );
    }
    
    @Override
    public boolean isFreeParameter( Parameter< ? > parameter, boolean deep,
                                    Set< HasParameters > seen ) {
      // TODO -- REVIEW -- not sure about this
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return false;
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return false;
      return HasParameters.Helper.isFreeParameter( this, parameter, deep, seen );
    }

    @Override
    public boolean hasParameter( Parameter< ? > parameter, boolean deep,
                                 Set< HasParameters > seen ) {
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return false;
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return false;
      return HasParameters.Helper.hasParameter( this, parameter, deep, seen );
    }

    @Override
    public boolean substitute( Parameter< ? > p1, Parameter< ? > p2,
                               boolean deep,
                               Set< HasParameters > seen ) {
      Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
      if ( pair.first ) return false;
      seen = pair.second;
      //if ( Utils.seen( this, deep, seen ) ) return false;
      return HasParameters.Helper.substitute( this, p1, p2, deep, seen );
    }

  }

  /**
   * For the convenience of referring to the effect method.
   */
  protected static Method setValueMethod1 = getSetValueMethod1();
  protected static Method setValueMethod2 = getSetValueMethod2();
  /**
   * Floating effects are those whose time or duration is changing. They must be
   * removed from TimeVaryingMap's map before they change; else, they will
   * corrupt the map. Before changing, they are placed in this floatingEffects
   * list, and after changing they are removed from this list and added back to
   * the map.
   */
  protected List< TimeValue > floatingEffects = new ArrayList< TimeValue >();

  protected String name;
  
  /**
   * 
   */
  public TimeVaryingMap( String name ) {
    super();
    this.name = name;
  }

  public TimeVaryingMap( String name, T defaultValue ) {
    super();
    this.name = name;
    Timepoint t = new Timepoint(StringDomain.typeMinValue, 0, this);
    //System.out.println(name + " put(" + t + ", " + defaultValue + ")" );
    put( t, defaultValue );
  }

  @SuppressWarnings( "unchecked" )
  public TimeVaryingMap( String name, Method initialValueFunction,
                         Object o, int samplePeriod, int horizonDuration ) {
    super();
    this.name = name;
    samplePeriod = correctSamplePeriod( samplePeriod, horizonDuration );
    try {
      for ( int t = 0; t < horizonDuration; t += samplePeriod ) {
        setValue( new Timepoint( "", t, this ),
                  (T)initialValueFunction.invoke( o, t ) );
      }
    } catch ( IllegalAccessException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch ( IllegalArgumentException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch ( InvocationTargetException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  public Timepoint getTimepointBefore( Timepoint t ) {
    return this.lowerKey( t );
  }
  
  protected static int correctSamplePeriod( int samplePeriod,
                                            int horizonDuration ) {
    if ( samplePeriod == 0 ) {
      samplePeriod = Math.max( 1, horizonDuration / 10 );
    }
    return samplePeriod;
  }
  
  @Override
  public void handleValueChangeEvent( Parameter< ? > parameter ) {
    for ( TimeValue e : floatingEffects ) {
      if ( e.hasParameter( parameter, true, null ) ) {
        put( e.first, e.second );
        //e.applyTo( this );
        floatingEffects.remove( e );
      }
    }
  }

  protected void floatEffects( Timepoint t ) {
    T value = get( t );
    floatingEffects.add( new TimeValue( t, value ) );
//    if ( effects != null ) {
//      if ( effects.second != null ) {
//        for ( Effect e : effects.second ) {
//          EffectInstance r = new EffectInstance( t, 1, e );
//          floatingEffects.add( r );
//        }
//      }
//      // REVIEW -- Should we check to see if the T value is a parameter and do
//      // an owner.handleValueChangeEvent()
//      // if ( effects.first != null ) {
//      // if ( effects.first instanceof ParameterListener ) {
//      // ((ParameterListener)effects.first).handleValueChangeEvent( ? )
//      // Make sure the value of the timeline is stales
//      if ( effects.first != null && effects.first instanceof LazyUpdate ) {
//        ( (LazyUpdate)effects.first ).setStale( true );
//      }
//    }
    remove( t );
  }

  protected void unfloatEffects( Timepoint t ) {
    for ( TimeValue e : floatingEffects ) {
      if ( e.first.compareTo( t ) == 0 ) {
        put( e.first, e.second );
      }
      floatingEffects.remove( e );
    }
//    for ( EffectInstance effectInstance : floatingEffects ) {
//      assert effectInstance.startTime != null;
//      if ( effectInstance.startTime.compareTo( t ) == 0 ) {
//        assert effectInstance.effect != null;
//        effectInstance.applyTo( this );
//      }
//    }
//    floatingEffects.remove( t );
  }

  @Override
  public void handleDomainChangeEvent( Parameter< ? > parameter ) {
    // TODO Auto-generated method stub
    Assert.assertTrue( "Not yet implemented!", false );
  }

  @Override
  public String getName() {
    if ( name != null && !name.isEmpty() ) return name;
    return getClass().getSimpleName();
  }

  public void setName( String newName ) {
    if ( newName == null || newName.isEmpty() ) {
      Formatter formatter = new Formatter(Locale.US);
      newName = getClass().getSimpleName();
    }
    this.name = newName;
  }

  // Add startTimes, durations, values that are Parameters, and (if deep)
  // parameters of effects.
  @Override
  public Set< Parameter< ? > > getParameters( boolean deep,
                                              Set< HasParameters > seen ) {
    //return Utils.getEmptySet();
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return Utils.getEmptySet();
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return Utils.getEmptySet();
    return HasParameters.Helper.getParameters( this, deep, seen );
  }

  @Override
  public Set< Parameter< ? > > getFreeParameters( boolean deep,
                                                  Set< HasParameters > seen ) {
    Assert.assertTrue( "This method is not supported!", false );
    return null;
  }
  @Override
  public void setFreeParameters( Set< Parameter< ? > > freeParams,
                                 boolean deep,
                                 Set< HasParameters > seen) {
    Assert.assertTrue( "This method is not supported!", false );
  }
  

  @Override
  public boolean hasParameter( Parameter< ? > parameter, boolean deep,
                               Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
    return HasParameters.Helper.hasParameter( this, parameter, deep, seen );
  }

  @Override
  public boolean
      substitute( Parameter< ? > p1, Parameter< ? > p2, boolean deep,
                  Set< HasParameters > seen ) {
    return HasParameters.Helper.substitute( this, p1, p2, deep, seen );
  }

  @Override
  public boolean isStale() {
    if ( !floatingEffects.isEmpty() ) return true;
    return ( HasParameters.Helper.isStale( this, false, null ) );
  }

  @Override
  public void setStale( boolean staleness ) {
    Debug.outln( "setStale(" + staleness + ") to " + this );
    Assert.assertTrue( "This method is not supported!", false );
  }

  @Override
  public void setStaleAnyReferencesTo( Parameter< ? > changedParameter ) {
    if ( containsKey( changedParameter ) ) {
      floatEffects( (Timepoint)changedParameter );
    }
    // TODO -- REVIEW -- should we float EffectInstances that have the parameter
    // and set a stale flag in EffeectInstances?
  }

  @Override
  public boolean refresh( Parameter< ? > parameter ) {
    // TODO -- REVIEW -- do nothing? owner's responsibility?
    // TODO -- TimeVaryingMap should own the values if they are Parameters and
    // maybe any Parameters the value has itself, unless the value is a
    // ParameterListener and owns its own Parameters.
    return false;
  }

  @Override
  public T getValue( Timepoint t ) {
    if ( t == null ) return null;
    T v = get( t ); //.first;
    if ( v != null ) return v;
    // Saving this check until later in case a null time value is acceptable,
    // and get(t) above works.
    if ( t.getValue() == null ) return null;
    return getValue( t.getValue() );
  }

  @Override
  public T getValue( Integer t ){
    Timepoint tp = new Timepoint( StringDomain.typeMaxValue, t, null );
    Entry< Timepoint, T > e = this.floorEntry( tp );
    if ( e != null ) return e.getValue();
    if ( !isEmpty() ) {
      return firstEntry().getValue();
    }
    return null;
  }

  public boolean hasValueAt( T value, Timepoint t ) {
    if ( t == null ) return false;
    T v = get( t ); //.first;
    if ( v != null ) return value.equals( v );
    // Saving this check until later in case a null time value is acceptable,
    // and get(t) above works.
    if ( t.getValue() == null ) return false;
    return hasValueAt( value, t.getValueNoPropagate() );
  }
  
  public Timepoint keyForValueAt( T value, Integer t ) {
    Timepoint tp = new Timepoint( null, t, null );
    Entry< Timepoint, T > e = this.floorEntry( tp );
    Timepoint startKey = null;
    if ( e != null ) {
      startKey = e.getKey();
    } else if ( !isEmpty() ) {
      startKey = firstEntry().getKey();
    } else {
      return null;
    }
    NavigableMap< Timepoint, T > tailMap = this.tailMap( startKey, true );
    for ( java.util.Map.Entry< Timepoint, T > te : tailMap.entrySet() ) {
      Object mVal = te.getValue();
      if ( Parameter.valuesEqual( value, mVal ) &&
           t.equals( te.getKey().getValueNoPropagate() ) ) {
        return te.getKey();
      }
      if ( te.getKey().getValueNoPropagate() > t ) break;
    }
//    } else if ( !isEmpty() ) {
//      Object mVal = firstEntry().getValue();
//      if ( ( ( value == null && mVal == null ) || ( value != null && value.equals( mVal ) ) ) &&
//          t.equals( firstEntry().getKey().getValueNoPropagate() ) ) {
//        return firstEntry().getKey();
//      }
//    }
    return null;
  }
  
  public boolean hasValueAt( T value, Integer t ) {
    return keyForValueAt( value, t ) != null;
  }

  public T setValue( Integer t, T value ) {
    Timepoint tp = keyForValueAt( value, t );
    if ( tp == null ) {
      tp = new Timepoint( "", t, null );
      return put( tp, value );
    }
    return null;
  }

  @Override
  public T setValue( Timepoint t, T value ) {
    Timepoint tp = keyForValueAt( value, t.getValue() );
    if ( tp != null && tp != t ) {
      remove( tp );
    }
    if ( tp != t ) {
      return put( t, value );
    }
    return null;
//    T oldValue = get( t );
//    assert ( oldValue == null || oldValue.equals( value ) );
    /*
    TimeValue valueAndEffects = get( t );
    if ( valueAndEffects == null ) {
      valueAndEffects = new TimeValue( value, new HashSet< Effect >() );
    } else {
      // TODO -- REVIEW -- Are two different effects setting the value at the
      // same time?!
      oldValue = valueAndEffects.first;
      valueAndEffects.first = value;
    }
    if ( valueAndEffects.second == null ) {
      valueAndEffects.second = new HashSet< Effect >();
    }
    EffectFunction setEffect = null;
    try {
      Vector< Object > args = new Vector< Object >();
      args.add( value );
      Method m =
          this.getClass().getMethod( "setValue",
                                     new Class[] { Timepoint.class,
                                                  value.getClass() } );
      // FIXME -- There should either be no effect stored, or the effect should
      // be passed as an argument.
      setEffect = new EffectFunction( this, m, args );
    } catch ( NoSuchMethodException | SecurityException e ) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }

    valueAndEffects.second.add( setEffect );
    return oldValue;
    */
  }

  @Override
  public T unsetValue( Timepoint t, T value ) {
    T oldValue = get( t );
//    T oldValue = null;
//    TimeValue valueAndEffects = get( t );
//    assert valueAndEffects != null;
//    oldValue = valueAndEffects.first;
    assert oldValue == value;
//    assert ( valueAndEffects.second == null || valueAndEffects.second.isEmpty() || valueAndEffects.second.size() == 1 );
    remove( t );
    return oldValue;
  }

  @Override
  public boolean isFreeParameter( Parameter< ? > parameter, boolean deep,
                                  Set< HasParameters > seen ) {
    Pair< Boolean, Set< HasParameters > > pair = Utils.seen( this, deep, seen );
    if ( pair.first ) return false;
    seen = pair.second;
    //if ( Utils.seen( this, deep, seen ) ) return false;
    return HasParameters.Helper.isFreeParameter( this, parameter, deep, seen );
  }

  @Override
  public int compareTo( TimeVarying< T > o ) {
    return Utils.intCompare( this.hashCode(), o.hashCode() );
    // TODO -- REVIEW -- Need this for DurativeEvents.effects map. Hash code
    // could cause a problem with small probability.
  }
  
  public static Method getSetValueMethod1() {
    if ( setValueMethod1 == null ) {
      for ( Method m : TimeVaryingMap.class.getMethods() ) {
        if ( m.getName().equals("setValue") && m.getParameterTypes() != null
             && m.getParameterTypes().length == 2 
             && m.getParameterTypes() [0] == Timepoint.class ) {
          setValueMethod1 = m;
        }
      }
    }
    return setValueMethod1;
  }
  
  public static Method getSetValueMethod2() {
    if ( setValueMethod2 == null ) {
      for ( Method m : TimeVaryingMap.class.getMethods() ) {
        if ( m.getName().equals("setValue") && m.getParameterTypes() != null
             && m.getParameterTypes().length == 2 
             && m.getParameterTypes() [0] == Integer.class) {
          setValueMethod2 = m;
        }
      }
    }
    return setValueMethod2;
  }

  @Override
  public boolean isApplied( Effect effect ) {
    return isApplied(effect, getSetValueMethod1(), getSetValueMethod2());
  }
  public boolean isApplied( Effect effect, Method method1, Method method2 ) {
    if ( !( effect instanceof EffectFunction ) ) {
      return false;
    }
    EffectFunction effectFunction = (EffectFunction)effect;
    if ( effectFunction == null || effectFunction.getMethod() == null ) {
      Debug.errln( this.getClass().getSimpleName() + ".isApplied(Effect="
                   + effect + ", Method=" + method1 + ", Method=" + method2
                   + ") called with no effect method! " + this );
      return false;
    }
    boolean isMethod1 = effectFunction.getMethod().equals(method1);
    boolean isMethod2 =  effectFunction.getMethod().equals( method2);
    if ( isMethod1  || isMethod2 ) {
      if ( effectFunction.arguments != null && effectFunction.arguments.size() >= 2 ) {
        Object t = effectFunction.arguments.get( 0 );
        Object o = effectFunction.arguments.get( 1 );
        T value = null;
        try {
          value = (T)o;
        } catch( Exception e ) {
          //e.printStackTrace();
        }
        if ( value != null ) {
          if ( t instanceof Timepoint ) {
            return hasValueAt( value, (Timepoint)t );
          } if ( t instanceof Integer ) {
            return hasValueAt( value, (Integer)t );
          }
        }
//          if ( isMethod1 || t instanceof Timepoint ) {
//            return hasValueAt( value, t );
//            return value.equals( getValue( (Timepoint) t ) );
//          }
//          if ( t instanceof Integer ) {
//            return value.equals(getValue((Integer) t));
//          } else if ( t instanceof Parameter ) {
//            Object v = ((Parameter<?>)t).getValueNoPropagate();
//            if ( v instanceof Integer ) {
//              return value.equals(getValue((Integer)v));
//            }
//          }
//        }
      }
    }
    return false;
  }
  
  @Override
  public String toString() {
    StringBuffer sb = new StringBuffer();
    sb.append( this.getName() );
    sb.append( super.toString() );
//    boolean first = true;
//    for ( Map.Entry< Timepoint, T > e : this.entrySet() ) {
//      if ( first ) {
//        first = false;
//      } else {
//        sb.append( ", " );
//      }
//      sb.append( e );
//    }
    return sb.toString();
  }

  @Override
  public < T > boolean pickValue( Variable< T > variable ) {
    // TODO Auto-generated method stub
    return false;
  }

}
