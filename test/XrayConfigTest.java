import com.nox.app.XrayConfig;
import java.nio.file.*;
import java.util.*;
public class XrayConfigTest {
  public static void main(String[] a) throws Exception {
    List<String> uris = Files.readAllLines(Paths.get(a[0]));
    int i=0;
    for (String u : uris) {
      if (u.isBlank()) continue;
      try {
        String j = XrayConfig.fromUri(u);
        Files.writeString(Paths.get(a[1], "cfg"+(i)+".json"), j);
        System.out.println("OK   "+u.substring(0,Math.min(40,u.length())));
      } catch (Exception e) { System.out.println("FAIL "+u.substring(0,Math.min(40,u.length()))+" -> "+e.getMessage()); }
      i++;
    }
  }
}
