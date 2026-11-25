import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.github.packageurl.PackageURL;
import org.owasp.dependencycheck.dependency.Confidence;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.dependency.Vulnerability;
import org.owasp.dependencycheck.dependency.naming.CpeIdentifier;
import org.owasp.dependencycheck.dependency.naming.PurlIdentifier;
import org.owasp.dependencycheck.xml.suppression.SuppressionParser;
import org.owasp.dependencycheck.xml.suppression.SuppressionRule;
import us.springett.parsers.cpe.CpeParser;

/** Offline rule-engine validation, intentionally outside the library classpath. */
public class DependencyCheckRuleCheck {
    /**
     * Applies the real engine to reviewed and negative identity fixtures.
     * @param args suppression XML and tab-separated fixture paths
     * @throws Exception if parsing fails or the engine violates the expectations
     */
    public static void main(String[] args) throws Exception {
        var rules = new SuppressionParser().parseSuppressionRules(new File(args[0]));
        if (rules.size() != 3) {
            throw new AssertionError("Expected three reviewed identity rules");
        }
        int total = 0;
        int removed = 0;
        Map<String, List<String[]>> grouped = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] values = line.split("\t", -1);
            grouped.computeIfAbsent(values[0], ignored -> new ArrayList<>()).add(values);
            Dependency dependency = new Dependency();
            dependency.setFileName("identity-fixture.jar");
            dependency.setFilePath("/fixture/identity-fixture.jar");
            dependency.addSoftwareIdentifier(new PurlIdentifier(new PackageURL(values[0]), Confidence.HIGHEST));
            dependency.addVulnerableSoftwareIdentifier(new CpeIdentifier(CpeParser.parse(values[1]), Confidence.HIGHEST));
            Vulnerability independentFinding = new Vulnerability();
            independentFinding.setName("CVE-2099-12345");
            dependency.addVulnerability(independentFinding);
            for (SuppressionRule rule : rules) {
                rule.process(dependency);
            }
            boolean suppressed = dependency.getVulnerableSoftwareIdentifiers().isEmpty();
            if (suppressed != Boolean.parseBoolean(values[2])) {
                throw new AssertionError("Unexpected engine identity decision: " + line);
            }
            if (!dependency.getVulnerabilities().contains(independentFinding)) {
                throw new AssertionError("A package-specific finding was suppressed");
            }
            if (suppressed) {
                removed++;
            }
            total++;
        }
        if (removed != 5 || rules.stream().anyMatch(rule -> !rule.isMatched())) {
            throw new AssertionError("Expected five identity corrections and no unused rules");
        }
        int mixed = 0;
        for (var entry : grouped.entrySet()) {
            Dependency dependency = new Dependency();
            dependency.setFileName("mixed-identity-fixture.jar");
            dependency.setFilePath("/fixture/mixed-identity-fixture.jar");
            dependency.addSoftwareIdentifier(new PurlIdentifier(new PackageURL(entry.getKey()), Confidence.HIGHEST));
            Set<CpeIdentifier> expected = new HashSet<>();
            boolean hasCorrection = false;
            for (String[] values : entry.getValue()) {
                CpeIdentifier identifier = new CpeIdentifier(CpeParser.parse(values[1]), Confidence.HIGHEST);
                dependency.addVulnerableSoftwareIdentifier(identifier);
                if (Boolean.parseBoolean(values[2])) {
                    hasCorrection = true;
                } else {
                    expected.add(identifier);
                }
            }
            Vulnerability independentFinding = new Vulnerability();
            independentFinding.setName("CVE-2099-12345");
            dependency.addVulnerability(independentFinding);
            for (SuppressionRule rule : rules) {
                rule.process(dependency);
            }
            if (!new HashSet<>(dependency.getVulnerableSoftwareIdentifiers()).equals(expected)) {
                throw new AssertionError("Unexpected grouped identity decision: " + entry.getKey());
            }
            if (!dependency.getVulnerabilities().contains(independentFinding)) {
                throw new AssertionError("A grouped package-specific finding was suppressed");
            }
            if (hasCorrection && !expected.isEmpty()) {
                mixed++;
            }
        }
        if (mixed != 3) {
            throw new AssertionError("Expected mixed corrected and retained identities for all three reviewed packages");
        }
        System.out.println("Real engine: " + total + " identity cases passed; " + removed
            + " corrected; " + (total - removed) + " retained; independent findings preserved");
        System.out.println("Grouped engine: " + grouped.size() + " packages passed; " + mixed
            + " mixed-identity packages; independent findings preserved");
    }
}
