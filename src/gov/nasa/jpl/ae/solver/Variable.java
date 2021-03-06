package gov.nasa.jpl.ae.solver;

import gov.nasa.jpl.mbee.util.HasId;

public interface Variable< T > extends HasDomain, HasId<Integer>, Wraps< T > {

  //public Domain< T > getDomain( boolean propagate, Set< HasDomain > seen );
  public Domain< T > getDomain();

  public void setDomain( Domain< T > domain );

  public T pickRandomValue();

  public boolean pickValue();

}
