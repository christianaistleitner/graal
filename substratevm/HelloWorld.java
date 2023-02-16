import java.util.*;

public class HelloWorld {
  public static void main(String[] args) {
    String str = "";
    Map<Object, Object> map = new HashMap<>();
    for (int i = 0; i < 100000000; i++) {
      map.put(new Object(), new Object());
      if (i % 10000 == 0) { str += String.valueOf(map.hashCode()).substring(0,1); map.clear(); }
    }
    System.out.println("Hello, World!\nSome data: " + str);
  }
}
