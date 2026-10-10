import dev.bluehouse.enablevolte.XmlDraftEditor;
import java.util.Map;

public final class XmlDraftCheck {
    public static void main(String[] args) throws Exception {
        String xml = "<ims><profile enabled='false'/><profile enabled='true'/></ims>";
        String updated = XmlDraftEditor.apply(xml, Map.of("ims/profile/@enabled [2]", "A & B"));
        if (!updated.contains("enabled=\"false\"") || !updated.contains("A &amp; B"))
            throw new AssertionError("Occurrence selection or XML escaping failed");
        try { XmlDraftEditor.apply(xml, Map.of("ims/missing/@enabled [1]", "true")); throw new AssertionError("Missing path accepted"); }
        catch (IllegalArgumentException expected) {}
        try { XmlDraftEditor.apply("<!DOCTYPE ims [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><ims/>", Map.of()); throw new AssertionError("DTD accepted"); }
        catch (org.xml.sax.SAXException expected) {}
        System.out.println("XML draft checks passed: duplicate attributes, escaping, missing keys, DTD rejection");
    }
}
