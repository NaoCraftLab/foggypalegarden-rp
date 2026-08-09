package com.naocraftlab

import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

final class ResourcePackVerifier {
    static final Map<String, String> SETTING_DEFAULTS = [
            fogRadius : '0.07',
            fogFade   : '12',
            minSkyLight: '4',
            maxHeight : '195',
    ].asImmutable()
    static final Set<String> SETTING_NAMES = SETTING_DEFAULTS.keySet().asImmutable()
    static final String BIOME_MODIFIER = 'assets/foggypalegarden/polytone/biome_modifiers/pale_garden.json'
    static final String RPO_DESCRIPTOR = "${BIOME_MODIFIER}.rpo"
    static final String PRESET_PREFIX = 'assets/respackopts/presets/'
    static final String LANG_PREFIX = 'assets/minecraft/lang/'

    private ResourcePackVerifier() {
    }

    static void requireState(Object condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message)
        }
    }

    static Object parseJson(String text, String label) {
        try {
            return new JsonSlurper().parseText(text)
        } catch (Exception exception) {
            throw new IllegalStateException("${label}: invalid JSON: ${exception.message}", exception)
        }
    }

    static Object parseJson5(String text, String label) {
        String normalized = text
                .replaceAll(/(?s)\/\*.*?\*\//, '')
                .replaceAll(/(?m)(^|\s)\/\/.*$/, '$1')
                .replaceAll(/([\{,]\s*)([A-Za-z_\$][A-Za-z0-9_\$]*)(\s*:)/, '$1"$2"$3')
                .replaceAll(/,\s*([}\]])/, '$1')
        try {
            return new JsonSlurper().parseText(normalized)
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "${label}: invalid supported JSON5 syntax: ${exception.message}", exception)
        }
    }

    static String expectedArchiveName(Map<String, String> properties) {
        String artifactVersion = TargetSpecification.artifactVersion(properties)
        return "${properties.rpName} ${properties.rpVersion}+${artifactVersion}.zip"
    }

    static void verify(File archiveFile, String target, Map<String, String> properties) {
        TargetSpecification.validate(properties, "target ${target}")
        boolean configurable = TargetSpecification.configurable(properties)
        boolean includePresets = TargetSpecification.includesPresets(properties)

        requireState(archiveFile.name == expectedArchiveName(properties),
                'archive filename does not match target properties')
        requireState(archiveFile.isFile(), "archive not found: ${archiveFile}")

        new ZipFile(archiveFile).withCloseable { ZipFile archive ->
            List entries = Collections.list(archive.entries())
            List<String> names = entries.collect { it.name }
            requireState(names.size() == names.toSet().size(), 'archive contains duplicate paths')
            requireState(names.every { String name ->
                !name.startsWith('/') && !name.split('/').contains('..')
            }, 'unsafe archive path')

            Map<String, byte[]> bytes = [:]
            entries.findAll { !it.directory }.each { entry ->
                bytes[entry.name] = archive.getInputStream(entry).withCloseable { it.bytes }
            }
            Set<String> requiredFiles = [
                    'LICENSE', 'CHANGELOG.md', 'pack.mcmeta', 'pack.png', BIOME_MODIFIER,
            ] as Set<String>
            List<String> missingFiles = (requiredFiles - names.toSet()).sort()
            requireState(missingFiles.empty, "archive misses files: ${missingFiles.join(', ')}")
            byte[] pngSignature = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]
            requireState(Arrays.equals(Arrays.copyOf(bytes['pack.png'], 8), pngSignature),
                    'pack.png is not a PNG')

            Closure<String> readText = { String name ->
                requireState(bytes.containsKey(name), "missing ${name}")
                new String(bytes[name], StandardCharsets.UTF_8)
            }

            verifyMetadata(parseJson(readText('pack.mcmeta'), 'pack.mcmeta'), properties)
            verifyLanguages(names, readText)
            verifyBiomeModifier(properties, configurable, readText)
            verifyRespackopts(names, properties, configurable, includePresets, readText)
            verifyPlaceholders(names, configurable, readText)
        }
    }

    private static void verifyMetadata(Object metadata, Map<String, String> properties) {
        requireState(metadata instanceof Map, 'pack.mcmeta root must be an object')
        Map pack = metadata.pack
        requireState(pack instanceof Map, 'pack.mcmeta must contain a pack object')
        int expectedMin = properties.specPackMinFormat.toInteger()
        int expectedMax = properties.specPackMaxFormat.toInteger()
        String metadataModel = TargetSpecification.packMetadata(properties)
        if (metadataModel == 'legacy') {
            requireState(number(pack.pack_format) == expectedMin, 'incorrect legacy pack_format')
            requireState(pack.supported_formats instanceof Map,
                    'legacy metadata needs supported_formats')
            requireState(number(pack.supported_formats.min_inclusive) == expectedMin,
                    'incorrect supported_formats minimum')
            requireState(number(pack.supported_formats.max_inclusive) == expectedMax,
                    'incorrect supported_formats maximum')
            requireState(!pack.containsKey('min_format') && !pack.containsKey('max_format'),
                    'legacy metadata contains modern fields')
        } else if (metadataModel == 'modern') {
            requireState(number(pack.min_format) == expectedMin, 'incorrect modern min_format')
            requireState(number(pack.max_format) == expectedMax, 'incorrect modern max_format')
            requireState(!pack.containsKey('pack_format'), 'modern-only metadata contains pack_format')
            requireState(!pack.containsKey('supported_formats'),
                    'modern-only metadata contains supported_formats')
        }
        requireState(pack.description instanceof Map, 'pack description must be translatable')
        requireState(pack.description.translate == 'rp.foggypalegarden.description',
                'incorrect description key')
        requireState(pack.description.fallback, 'pack description needs a fallback')
    }

    private static int number(Object value) {
        requireState(value instanceof Number, "expected a number, got ${value}")
        ((Number) value).intValue()
    }

    private static void verifyLanguages(List<String> names, Closure<String> readText) {
        List<String> languageNames = names.findAll {
            it.startsWith(LANG_PREFIX) && it.endsWith('.json')
        }.sort()
        requireState(!languageNames.empty, 'no language files found')
        String englishName = "${LANG_PREFIX}en_us.json"
        requireState(languageNames.contains(englishName), 'missing en_us.json')
        Object english = parseJson(readText(englishName), englishName)
        requireState(english instanceof Map, 'en_us.json must be an object')
        Set<String> expectedKeys = english.keySet()
        requireState(expectedKeys.contains('rpo.foggypalegarden'),
                'English pack title key is missing')
        languageNames.each { String name ->
            Object translation = parseJson(readText(name), name)
            requireState(translation instanceof Map, "${name} must be an object")
            List<String> missing = (expectedKeys - translation.keySet()).sort()
            List<String> extra = (translation.keySet() - expectedKeys).sort()
            requireState(missing.empty, "${name} misses keys: ${missing.join(', ')}")
            requireState(extra.empty, "${name} has unexpected keys: ${extra.join(', ')}")
        }
    }

    private static void verifyBiomeModifier(Map<String, String> properties,
                                            boolean configurable,
                                            Closure<String> readText) {
        String raw = readText(BIOME_MODIFIER)
        Set<String> placeholders = (raw =~ /\$\{(\w+)\}/).collect { it[1] }.toSet()
        Set<String> expected = configurable ? SETTING_NAMES : [] as Set<String>
        requireState(placeholders == expected,
                "biome placeholders are ${placeholders.sort()}, expected ${expected.sort()}")
        String expanded = raw
        SETTING_DEFAULTS.each { key, value -> expanded = expanded.replace("\${${key}}", value) }
        Object modifier = parseJson(expanded, BIOME_MODIFIER)
        requireState(modifier instanceof Map, 'biome modifier must be an object')
        Object expectedTargets = parseJson(properties.specPaleGardenTargets,
                'specPaleGardenTargets')
        requireState(modifier.targets == expectedTargets,
                'biome targets do not match target properties')
        if (properties.specFogModel == 'legacy') {
            requireState(modifier.containsKey('fog_radius') && modifier.containsKey('fog_fade'),
                    'legacy fog fields are missing')
            requireState(!modifier.containsKey('attributes_modifiers'),
                    'legacy modifier contains modern attributes')
            [modifier.fog_radius, modifier.fog_fade].each { expression ->
                requireState(expression instanceof String, 'legacy fog values must be expressions')
                requireState(expression.contains('SKY_LIGHT'),
                        'legacy expression misses sky-light gate')
                requireState(expression.contains('POS_Y'), 'legacy expression misses height gate')
                requireState(expression.contains('lerp(') && expression.contains('step('),
                        'legacy gate expression is incomplete')
            }
        } else if (properties.specFogModel == 'modern') {
            requireState(!modifier.containsKey('fog_radius') && !modifier.containsKey('fog_fade'),
                    'modern modifier contains legacy fields')
            Map attributes = modifier.attributes_modifiers
            requireState(attributes instanceof Map, 'modern attributes_modifiers is missing')
            Set<String> expectedAttributes = [
                    'minecraft:visual/fog_start_distance',
                    'minecraft:visual/fog_end_distance',
            ] as Set<String>
            requireState(attributes.keySet() == expectedAttributes,
                    'modern modifier changes unexpected attributes')
            String start = attributes['minecraft:visual/fog_start_distance']
            String end = attributes['minecraft:visual/fog_end_distance']
            [start, end].each { expression ->
                requireState(expression instanceof String, 'modern distances must be expressions')
                requireState(expression.contains('c.skyLight()'),
                        'modern expression misses sky-light gate')
                requireState(expression.contains('c.y()'), 'modern expression misses height gate')
                requireState(expression.contains('c.viewDistance()'),
                        'modern expression misses render-distance scaling')
            }
            requireState(start.contains('clamp(c.viewDistance() / 10.0, 4.0, 64.0)'),
                    'modern fade-width formula changed')
            requireState(start.endsWith(': 0.0'), 'modern neutral fog start must be 0')
            requireState(end.endsWith(': 1024.0'), 'modern neutral fog end must be 1024')
        } else {
            throw new IllegalStateException("unknown specFogModel: ${properties.specFogModel}")
        }
    }

    private static void verifyRespackopts(List<String> names,
                                          Map<String, String> properties,
                                          boolean configurable,
                                          boolean includePresets,
                                          Closure<String> readText) {
        List<String> presetNames = names.findAll {
            it.startsWith(PRESET_PREFIX) && it.endsWith('.json5')
        }.sort()
        if (!configurable) {
            requireState(!names.contains('respackopts.json5'),
                    'static archive contains Respackopts config')
            requireState(!names.contains(RPO_DESCRIPTOR),
                    'static archive contains an RPO descriptor')
            requireState(presetNames.empty, 'static archive contains presets')
            return
        }
        Object config = parseJson5(readText('respackopts.json5'), 'respackopts.json5')
        requireState(config instanceof Map, 'Respackopts config must be an object')
        requireState(config.id == 'foggypalegarden', 'incorrect Respackopts pack id')
        requireState(number(config.version) == properties.specRespackoptsVersion.toInteger(),
                'incorrect Respackopts format')
        requireState(config.capabilities == ['FileFilter'], 'only FileFilter should be enabled')
        requireState(config.conf instanceof Map && config.conf.keySet() == SETTING_NAMES,
                'Respackopts setting set changed')
        Object descriptor = parseJson5(readText(RPO_DESCRIPTOR), RPO_DESCRIPTOR)
        requireState(descriptor instanceof Map, 'RPO descriptor must be an object')
        requireState(descriptor.expansions instanceof Map &&
                descriptor.expansions.keySet() == SETTING_NAMES, 'RPO expansion set changed')
        SETTING_NAMES.each { setting ->
            requireState(descriptor.expansions[setting] == "foggypalegarden.${setting}",
                    "incorrect expansion for ${setting}")
        }
        if (includePresets) {
            Set<String> expectedPresets = [
                    "${PRESET_PREFIX}1_stephen_king.json5",
                    "${PRESET_PREFIX}2_i_am_not_afraid_but.json5",
                    "${PRESET_PREFIX}3_ambiance.json5",
            ] as Set<String>
            requireState(presetNames.toSet() == expectedPresets, 'preset files are incomplete')
            presetNames.each { name ->
                Object preset = parseJson5(readText(name), name)
                requireState(preset instanceof Map, "${name} must be an object")
                requireState(preset.keySet() == ['fogRadius', 'fogFade'] as Set<String>,
                        "${name} must only set density controls")
            }
        } else {
            requireState(presetNames.empty, 'target format does not support presets')
        }
    }

    private static void verifyPlaceholders(List<String> names,
                                           boolean configurable,
                                           Closure<String> readText) {
        List<List<String>> occurrences = []
        names.findAll { name ->
            ['.json', '.json5', '.rpo', '.mcmeta', '.md'].any { name.endsWith(it) }
        }.each { String name ->
            (readText(name) =~ /\$\{(\w+)\}/).each { match ->
                occurrences << [name, match[1]]
            }
        }
        if (configurable) {
            requireState(!occurrences.empty, 'configurable archive contains no runtime placeholders')
            occurrences.each { occurrence ->
                requireState(occurrence[0] == BIOME_MODIFIER,
                        "runtime placeholder leaked into ${occurrence[0]}")
                requireState(SETTING_NAMES.contains(occurrence[1]),
                        "unexpected placeholder ${occurrence[1]}")
            }
        } else {
            requireState(occurrences.empty,
                    "static archive contains unresolved placeholders: ${occurrences}")
        }
    }
}
