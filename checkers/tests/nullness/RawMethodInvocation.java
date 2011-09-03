import checkers.nullness.quals.*;
import java.util.*;
@checkers.quals.DefaultQualifier("Nullable")
class RawMethodInvocation {
  int a;
  int b;

  // TODO: This should be legal
  //:: warning: (fields.uninitialized)
  RawMethodInvocation(boolean constructor_inits_a) {
    a = 1;
    // TODO: This should be legal.  See issue 75: http://code.google.com/p/checker-framework/issues/detail?id=75
    //:: error: (method.invocation.invalid)
    init_b();
  }

  void init_b() @Raw {
    b = 2;
  }

  // TODO: This should be legal
  //:: warning: (fields.uninitialized)
  RawMethodInvocation(int constructor_inits_none) {
    // TODO: This should be legal.  See issue 75: http://code.google.com/p/checker-framework/issues/detail?id=75
    //:: error: (method.invocation.invalid)
    init_ab();
  }

  void init_ab() @Raw {
    a = 1;
    b = 2;
  }

  void nonRawMethod() { }
}
