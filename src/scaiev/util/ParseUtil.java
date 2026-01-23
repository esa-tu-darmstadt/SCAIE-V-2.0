package scaiev.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class ParseUtil {
  /**
   * A parsing-related Exception
   */
  public static class ParseException extends Exception {

    private static final long serialVersionUID = -8482249950021926678L;

    public ParseException(String message) {
      super(message);
    }

    public ParseException(Throwable cause) {
      super(cause);
    }

    public ParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Retrieves the boxed class for a primitive class.
   * @param primitiveClass the primitive class (e.g. int.class)
   * @return the corresponding boxed class (e.g. Integer.class)
   * @throws IllegalArgumentException on unexpected primitiveClass
   */
  private static Class<?> getBoxedClass(Class<?> primitiveClass) {
    // Seems like this should be a language feature already?
    if (primitiveClass.equals(boolean.class)) return Boolean.class;
    if (primitiveClass.equals(byte.class)) return Byte.class;
    if (primitiveClass.equals(short.class)) return Short.class;
    if (primitiveClass.equals(char.class)) return Character.class;
    if (primitiveClass.equals(int.class)) return Integer.class;
    if (primitiveClass.equals(long.class)) return Long.class;
    if (primitiveClass.equals(float.class)) return Float.class;
    if (primitiveClass.equals(double.class)) return Double.class;
    if (primitiveClass.equals(void.class)) return Void.class;
    assert(!primitiveClass.isPrimitive()); //checked all native language primitive types
    throw new IllegalArgumentException("Not a primitive type: " + primitiveClass.getName());
  }
  /**
   * Converts a Map&lt;String,Object&gt; key-value collection into a record type instance.
   * Supports nested record types.
   * @param <T> the record type
   * @param inputs the input key-value collection (String keys)
   * @param cls the record type Class
   * @return a new instance of the record type
   * @throws IllegalArgumentException if the given type is not a record type
   * @throws ParseException on parse errors (key not in record type, instantiation/type conversion errors)
   */
  public static <T> T recordFromMap(Map<?,?> inputs, Class<T> cls) throws ParseException {
    if (!cls.isRecord())
      throw new IllegalArgumentException("not a record type");
    // Kind of inefficient, but this way, we can check for both missing inputs as well as unexpected input keys.
    Map<String,Object> orderedComponentMap = new LinkedHashMap<>();
    Arrays.stream(cls.getRecordComponents()).map(RecordComponent::getName).forEach(x->orderedComponentMap.put(x, null));
    for (var entry : inputs.entrySet()) {
      if (!(entry.getKey() instanceof String))
        throw new ParseException("Unexpected non-String map key");
      if (!orderedComponentMap.containsKey(entry.getKey())) {
        throw new ParseException("Key %s not found in %s".formatted(entry.getKey(), cls.getName()));
      }
      orderedComponentMap.put((String)entry.getKey(), entry.getValue());
    }

    Class<?>[] paramTypes =
      Arrays.stream(cls.getRecordComponents())
            .map(RecordComponent::getType)
            .toArray(Class<?>[]::new);
    java.lang.reflect.Constructor<T> constr;
    try {
      constr = cls.getDeclaredConstructor(paramTypes);
    } catch (NoSuchMethodException e) {
      throw new ParseException("Broken record type %s".formatted(cls.getName()), e);
    }
    Object[] constrArgs = new Object[orderedComponentMap.size()];
    int i = -1;
    for (var entry : orderedComponentMap.entrySet()) {
      ++i;
      Object val = null;
      // isAssignableFrom doesn't report boxed primitive -> primitive assigns like Boolean -> boolean.
      if (entry.getValue() != null
          && !paramTypes[i].isInstance(entry.getValue())
          && !(paramTypes[i].isPrimitive() && getBoxedClass(paramTypes[i]).isInstance(entry.getValue()))) {
        // Cannot directly convert to the record field.
        if (paramTypes[i].isRecord() && (entry.getValue() instanceof Map)) {
          // Try to construct record-type field from a Map input.
          Map<?,?> subMap = (Map<?,?>)entry.getValue();
          val = recordFromMap(subMap, paramTypes[i]);
        }
        else {
          throw new ParseException("Cannot assign %s to %s".formatted(entry.getValue().getClass().getName(), paramTypes[i].getName()));
        }
      }
      else {
        // Direct conversion possible.
        val = paramTypes[i].isPrimitive() ? entry.getValue() : paramTypes[i].cast(entry.getValue());
      }
      constrArgs[i] = val;
    }
    try {
      return constr.newInstance(constrArgs);
    } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new ParseException("Cannot instantiate %s".formatted(cls.getName()), e);
    }
  }
}
