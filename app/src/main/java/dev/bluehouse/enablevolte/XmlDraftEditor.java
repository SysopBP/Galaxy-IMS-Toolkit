package dev.bluehouse.enablevolte;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

public final class XmlDraftEditor {
    private XmlDraftEditor() {}
    public static String apply(String xml, Map<String, String> values) throws Exception {
        if (xml.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("XML too large");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        org.w3c.dom.Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        Set<String> applied = new HashSet<>();
        visit(document.getDocumentElement(), document.getDocumentElement().getTagName(), values, new HashMap<>(), applied);
        if (!applied.containsAll(values.keySet())) throw new IllegalArgumentException("Some attributes were not found");
        StringWriter out = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(out));
        return out.toString();
    }
    private static void visit(Element element, String path, Map<String, String> values,
                              Map<String, Integer> counts, Set<String> applied) {
        for (int index = 0; index < element.getAttributes().getLength(); index++) {
            Node attribute = element.getAttributes().item(index);
            String base = path + "/@" + attribute.getNodeName();
            int occurrence = counts.getOrDefault(base, 0) + 1;
            counts.put(base, occurrence);
            String key = base + " [" + occurrence + "]";
            if (values.containsKey(key)) {
                attribute.setNodeValue(values.get(key)); applied.add(key);
            }
        }
        for (int index = 0; index < element.getChildNodes().getLength(); index++) {
            Node child = element.getChildNodes().item(index);
            if (child instanceof Element) visit((Element) child, path + "/" + ((Element) child).getTagName(), values, counts, applied);
        }
    }
}
