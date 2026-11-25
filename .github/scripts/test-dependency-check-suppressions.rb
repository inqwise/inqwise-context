#!/usr/bin/env ruby
require 'date'
require 'json'
require 'open3'
require 'rexml/document'
require 'tmpdir'

ROOT = File.expand_path('../..', __dir__)
SOURCE = File.read(File.join(ROOT, '.github/dependency-check-suppressions.xml'))
NAMESPACE = 'https://jeremylong.github.io/DependencyCheck/dependency-suppression.1.4.xsd'
ELASTIC_CPES = %w[cpe:2.3:a:elastic:elasticsearch:8.15.3:*:*:*:*:*:*:*
                  cpe:2.3:a:elasticsearch:elasticsearch:8.15.3:*:*:*:*:*:*:*].freeze
EXPECTED = {
  'pkg:maven/co.elastic.clients/elasticsearch-java@8.15.3' => ELASTIC_CPES,
  'pkg:maven/org.elasticsearch.client/elasticsearch-rest-client@8.15.3' => ELASTIC_CPES,
  'pkg:maven/io.vertx/vertx-hazelcast@5.2.3' => ['cpe:2.3:a:hazelcast:hazelcast:5.2.3:*:*:*:*:*:*:*']
}.freeze
CPE_PATTERNS = {
  ELASTIC_CPES[0] => '\Acpe:/a:elastic:elasticsearch:8\.15\.3\z',
  ELASTIC_CPES[1] => '\Acpe:/a:elasticsearch:elasticsearch:8\.15\.3\z',
  EXPECTED.values.last.first => '\Acpe:/a:hazelcast:hazelcast:5\.2\.3\z'
}.freeze

def attribute_values(element)
  values = {}
  element.attributes.each_attribute { |attribute| values[attribute.expanded_name] = attribute.value }
  values
end

def parse_rules(source, today: Time.now.utc.to_date)
  raise ArgumentError, 'DTD declarations are not allowed' if source.include?('<!DOCTYPE')
  root = REXML::Document.new(source).root
  raise ArgumentError, 'Wrong suppression namespace' unless root.name == 'suppressions' && root.namespace == NAMESPACE
  rules = {}
  root.elements.each do |rule|
    raise ArgumentError, 'Only individually expiring rules are allowed' unless rule.name == 'suppress' &&
      attribute_values(rule) == { 'until' => '2027-02-23Z' }
    raise ArgumentError, 'Expired suppression rule' unless Date.strptime(rule.attributes['until'], '%Y-%m-%dZ') > today
    children = rule.elements.to_a
    raise ArgumentError, 'Only product identity corrections are allowed' unless children.all? { |node| %w[notes packageUrl cpe].include?(node.name) }
    raise ArgumentError, 'Expected one package and one explanation' unless children.count { |node| node.name == 'packageUrl' } == 1 &&
      children.count { |node| node.name == 'notes' } == 1
    raise ArgumentError, 'Missing review evidence' unless rule.elements['notes'].text.include?('Reviewed 2026-11-25') &&
      rule.elements['notes'].text.include?('https://')
    selectors = children.reject { |node| node.name == 'notes' }
    raise ArgumentError, 'Unexpected selector attributes' unless selectors.all? do |node|
      expected = node.name == 'cpe' ? { 'regex' => 'true', 'caseSensitive' => 'true' } : { 'caseSensitive' => 'true' }
      attribute_values(node) == expected
    end
    package = rule.elements['packageUrl'].text
    cpes = children.select { |node| node.name == 'cpe' }.map(&:text)
    raise ArgumentError, 'Unexpected or duplicate package scope' if rules.key?(package) || !EXPECTED.key?(package)
    raise ArgumentError, 'Unexpected product correction' unless cpes.sort == EXPECTED.fetch(package).map { |cpe| CPE_PATTERNS.fetch(cpe) }.sort
    # The report uses CPE 2.3; the engine matches normalized 2.2 URIs.
    rules[package] = EXPECTED.fetch(package)
  end
  raise ArgumentError, 'Expected exactly the reviewed package set' unless rules.keys.sort == EXPECTED.keys.sort
  rules
end

rules = parse_rules(SOURCE)
parse_rules(SOURCE, today: Date.new(2027, 2, 22))
[Date.new(2027, 2, 23), Date.new(2027, 2, 24)].each do |today|
  begin
    parse_rules(SOURCE, today: today)
  rescue ArgumentError => error
    raise unless error.message == 'Expired suppression rule'
    next
  end
  abort "Expired suppression was accepted on #{today}"
end
positive = EXPECTED.flat_map { |package, cpes| cpes.map { |cpe| [package, cpe] } }
negative = [
  ['pkg:maven/org.elasticsearch/elasticsearch@8.15.3', ELASTIC_CPES.first],
  ['pkg:maven/co.elastic.clients/elasticsearch-java@8.15.4', ELASTIC_CPES.first],
  [EXPECTED.keys.first, 'cpe:2.3:a:elastic:elasticsearch:8.15.30:*:*:*:*:*:*:*'],
  [EXPECTED.keys.first, 'cpe:2.3:a:elastic:elasticsearch:8.15.3:update1:*:*:*:*:*:*'],
  ['pkg:maven/org.elasticsearch.client/elasticsearch-rest-client@8.19.0', ELASTIC_CPES.first],
  ['pkg:maven/io.vertx/vertx-hazelcast@5.2.4', 'cpe:2.3:a:hazelcast:hazelcast:5.2.3:*:*:*:*:*:*:*'],
  ['pkg:maven/com.hazelcast/hazelcast@5.2.3', 'cpe:2.3:a:hazelcast:hazelcast:5.2.3:*:*:*:*:*:*:*'],
  ['pkg:maven/com.hazelcast/hazelcast@5.7.0', 'cpe:2.3:a:hazelcast:hazelcast:5.7.0:*:*:*:*:*:*:*'],
  ['pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.21.2', 'cpe:2.3:a:fasterxml:jackson-databind:2.21.2:*:*:*:*:*:*:*'],
  ['pkg:maven/tools.jackson.core/jackson-databind@3.1.2', 'cpe:2.3:a:fasterxml:jackson-databind:3.1.2:*:*:*:*:*:*:*'],
  [EXPECTED.keys.first, 'cpe:2.3:a:fasterxml:jackson-databind:2.21.2:*:*:*:*:*:*:*'],
  ['pkg:maven/org.elasticsearch.client/elasticsearch-rest-client@8.15.3', 'cpe:2.3:a:fasterxml:jackson-databind:2.21.2:*:*:*:*:*:*:*'],
  ['pkg:maven/io.vertx/vertx-hazelcast@5.2.3', 'cpe:2.3:a:fasterxml:jackson-databind:2.21.2:*:*:*:*:*:*:*']
]
positive.each { |package, cpe| abort 'Reviewed correction did not match' unless rules.fetch(package, []).include?(cpe) }
negative.each { |package, cpe| abort 'Unrelated product was suppressed' if rules.fetch(package, []).include?(cpe) }
mutations = [
  SOURCE.sub('2027-02-23Z', '2028-02-23Z'),
  SOURCE.sub('caseSensitive="true"', 'regex="true"'),
  SOURCE.sub('<packageUrl caseSensitive="true">', '<packageUrl>'),
  SOURCE.sub('elasticsearch-java@8.15.3', 'elasticsearch-java@.*'),
  SOURCE.sub('<cpe regex="true" caseSensitive="true">', '<cve>').sub('</cpe>', '</cve>'),
  SOURCE.sub('</notes>', '</notes><cvssBelow>10</cvssBelow>'),
  SOURCE.sub('8\.15\.3\z', '8\.15\.3'),
  SOURCE.sub('regex="true" caseSensitive="true"', 'regex="false" caseSensitive="true"'),
  SOURCE.sub(CPE_PATTERNS.fetch(ELASTIC_CPES.first), ELASTIC_CPES.first),
  SOURCE.sub('pkg:maven/io.vertx/vertx-hazelcast@5.2.3', 'pkg:maven/com.hazelcast/hazelcast@5.7.0')
]
mutations.each do |candidate|
  begin
    parse_rules(candidate)
  rescue ArgumentError, REXML::ParseException
    next
  end
  abort 'Broadened or invalid suppression was accepted'
end
engine_cases = positive.map { |package, cpe| [package, cpe, true] } + negative.map { |package, cpe| [package, cpe, false] }
if ENV['DEPENDENCY_CHECK_REPORT']
  report = JSON.parse(File.read(ENV.fetch('DEPENDENCY_CHECK_REPORT')))
  report.fetch('dependencies').each do |dependency|
    dependency.fetch('packages', []).each do |package|
      dependency.fetch('vulnerabilityIds', []).each do |identity|
        engine_cases << [package.fetch('id'), identity.fetch('id'), rules.fetch(package.fetch('id'), []).include?(identity.fetch('id'))]
      end
    end
  end
  matches = report.fetch('dependencies').sum do |dependency|
    dependency.fetch('packages', []).sum do |package|
      dependency.fetch('vulnerabilityIds', []).count { |cpe| rules.fetch(package.fetch('id'), []).include?(cpe.fetch('id')) }
    end
  end
  abort 'Unexpected CI report identity matches' unless matches == positive.length
  bundled_packages = %w[pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.21.2
                         pkg:maven/tools.jackson.core/jackson-databind@3.1.2]
  bundled = report.fetch('dependencies').select do |dependency|
    dependency.fetch('packages', []).any? { |package| bundled_packages.include?(package.fetch('id')) }
  end
  abort 'Expected both bundled Jackson components in this CI report' unless bundled.length == 2
  bundled.each do |dependency|
    findings = dependency.fetch('vulnerabilities', []).map { |vulnerability| vulnerability.fetch('name') }
    abort 'Expected shaded Jackson highs to remain visible' unless %w[CVE-2026-54512 CVE-2026-54513].all? { |id| findings.include?(id) }
    dependency.fetch('packages').each do |package|
      abort 'Bundled Jackson was suppressed' unless rules.fetch(package.fetch('id'), []).empty?
    end
  end
  puts "Validated #{matches} exact identity corrections against the CI report; this is not a rescan"
  puts 'Preserved the reported high findings in both shaded Jackson components'
end
if ENV['DEPENDENCY_CHECK_ENGINE_CLASSPATH']
  Dir.mktmpdir('dependency-check-engine-test.') do |directory|
    cases = File.join(directory, 'cases.tsv')
    File.write(cases, engine_cases.uniq.map { |row| row.join("\t") }.join("\n") + "\n")
    java = ENV['JAVA_HOME'] ? File.join(ENV.fetch('JAVA_HOME'), 'bin/java') : 'java'
    stdout, stderr, status = Open3.capture3(java, '--class-path', ENV.fetch('DEPENDENCY_CHECK_ENGINE_CLASSPATH'),
      File.join(__dir__, 'DependencyCheckRuleCheck.java'), File.join(ROOT, '.github/dependency-check-suppressions.xml'), cases)
    abort "Dependency-Check engine validation failed:\n#{stdout}#{stderr}" unless status.success?
    puts stdout
  end
end
puts "Passed #{positive.length} positive, #{negative.length} negative, and #{mutations.length} suppression mutation checks"
puts 'Passed 3 UTC expiry boundary checks'
