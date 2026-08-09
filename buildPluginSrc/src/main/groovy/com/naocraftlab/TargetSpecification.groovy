package com.naocraftlab

final class TargetSpecification {
    static final Set<String> DEPRECATED_PROPERTIES = [
            'specPackMetadata', 'specPackFormat', 'specConfigurable',
            'specIncludePresets', 'specLoaders',
    ] as Set<String>
    static final Set<String> STATIC_FOG_PROPERTIES = [
            'fogRadius', 'fogFade', 'minSkyLight', 'maxHeight',
    ] as Set<String>
    private static final Set<String> REQUIRED_PROPERTIES = [
            'rpName', 'rpVersion', 'specMcVersions', 'specPaleGardenTargets',
            'specFogModel', 'specPackMinFormat', 'specPackMaxFormat',
            'specReleaseType',
    ] as Set<String>

    private TargetSpecification() {
    }

    static void validateCommon(Map<String, String> properties, String label) {
        Set<String> expected = ['rpName', 'rpVersion'] as Set<String>
        List<String> missing = expected.findAll { !properties[it] }.sort()
        List<String> unexpected = (properties.keySet() - expected).sort()
        requireState(missing.empty,
                "${label} is missing global properties: ${missing.join(', ')}")
        requireState(unexpected.empty,
                "${label} must only contain rpName and rpVersion; found: " +
                        unexpected.join(', '))
    }

    static void validateOverrides(Map<String, String> properties, String label) {
        List<String> overriddenGlobals = (properties.keySet().intersect(
                ['rpName', 'rpVersion'] as Set<String>)).sort()
        requireState(overriddenGlobals.empty,
                "${label} must not override global properties: ${overriddenGlobals.join(', ')}")
    }

    static void validate(Map<String, String> properties, String label) {
        List<String> deprecated = (DEPRECATED_PROPERTIES.intersect(properties.keySet())).sort()
        requireState(deprecated.empty,
                "${label} uses derived properties that must be removed: ${deprecated.join(', ')}")
        List<String> missing = REQUIRED_PROPERTIES.findAll { !properties[it] }.sort()
        requireState(missing.empty,
                "${label} is missing required properties: ${missing.join(', ')}")
        requireState(properties.specFogModel in ['legacy', 'modern'],
                "${label}: specFogModel must be legacy or modern")
        requireState(properties.specReleaseType in ['release', 'beta'],
                "${label}: specReleaseType must be release or beta")

        int minimum = positiveInteger(properties.specPackMinFormat,
                "${label}:specPackMinFormat")
        int maximum = positiveInteger(properties.specPackMaxFormat,
                "${label}:specPackMaxFormat")
        requireState(minimum <= maximum,
                "${label}: specPackMinFormat must not exceed specPackMaxFormat")
        requireState(maximum < 65 || minimum >= 65,
                "${label}: a target must not cross the pack-metadata format 65 boundary")

        if (configurable(properties)) {
            positiveInteger(properties.specRespackoptsVersion,
                    "${label}:specRespackoptsVersion")
            List<String> bakedSettings = STATIC_FOG_PROPERTIES.findAll {
                properties.containsKey(it)
            }.sort()
            requireState(bakedSettings.empty,
                    "${label}: configurable targets must not bake settings: " +
                            bakedSettings.join(', '))
        } else {
            List<String> missingDefaults = STATIC_FOG_PROPERTIES.findAll {
                !properties[it]
            }.sort()
            requireState(missingDefaults.empty,
                    "${label}: static targets need baked fog defaults: " +
                            missingDefaults.join(', '))
        }
    }

    static boolean configurable(Map<String, String> properties) {
        properties.containsKey('specRespackoptsVersion')
    }

    static boolean includesPresets(Map<String, String> properties) {
        configurable(properties) && properties.specRespackoptsVersion.toInteger() >= 13
    }

    static String packMetadata(Map<String, String> properties) {
        properties.specPackMinFormat.toInteger() >= 65 ? 'modern' : 'legacy'
    }

    static String artifactVersion(Map<String, String> properties) {
        properties.specArtifactVersion?.trim() ?:
                properties.specMcVersions.split(',', 2)[0].trim()
    }

    private static int positiveInteger(String value, String label) {
        try {
            int parsed = value.toInteger()
            requireState(parsed > 0, "${label} must be positive")
            parsed
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("${label} must be an integer, got '${value}'", exception)
        }
    }

    private static void requireState(Object condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message)
        }
    }
}
