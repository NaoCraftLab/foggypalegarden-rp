package com.naocraftlab

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

abstract class PublishReleaseTask extends DefaultTask {
    private static final String USER_AGENT =
            'NaoCraftLab/Foggy-Pale-Garden-Publisher ' +
                    '(https://github.com/NaoCraftLab/foggypalegarden-rp)'

    @Internal
    abstract DirectoryProperty getRepositoryDirectory()

    @InputDirectory
    abstract DirectoryProperty getReleasesDirectory()

    @InputFile
    abstract RegularFileProperty getGradlePropertiesFile()

    @InputFile
    abstract RegularFileProperty getChangelogFile()

    @InputFiles
    abstract ConfigurableFileCollection getTargetPropertyFiles()

    @Input
    abstract Property<Boolean> getPreflightOnly()

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    PublishReleaseTask() {
        outputs.upToDateWhen { false }
        preflightOnly.convention(false)
    }

    @TaskAction
    void publish() {
        File repository = repositoryDirectory.get().asFile
        Map<String, String> common = PublicationSupport.loadProperties(
                gradlePropertiesFile.get().asFile)
        String packVersion = common.rpVersion
        PublicationSupport.requireState(packVersion ==~ /\d+\.\d+\.\d+/,
                "rpVersion must use X.Y.Z, got '${packVersion}'")
        String changelog = PublicationSupport.extractChangelog(
                changelogFile.get().asFile, packVersion)
        List<Map<String, Object>> targets = PublicationSupport.buildTargets(
                repository, releasesDirectory.get().asFile, common, changelog)

        String modrinthToken = requireSecret('MODRINTH_TOKEN')
        String curseforgeApiKey = requireSecret('CURSEFORGE_API_KEY')
        String curseforgeUploadToken = requireSecret('CURSEFORGE_UPLOAD_TOKEN')
        Map<String, String> github = preflightOnly.get() ? null : validateGithubMain()
        Map<String, List<Map<String, Object>>> remote = [
                modrinth : fetchModrinth(modrinthToken),
                curseforge: fetchCurseforge(curseforgeApiKey),
        ]
        Map<String, Map<String, Map<String, Object>>> plan = classifyAll(targets, remote)
        appendSummary('Publication preflight', summaryRows(targets, plan))
        logger.lifecycle(renderPlan(targets, plan))
        requireNoConflicts(targets, plan)
        if (preflightOnly.get()) {
            return
        }

        remote = [
                modrinth : fetchModrinth(modrinthToken),
                curseforge: fetchCurseforge(curseforgeApiKey),
        ]
        Map<String, Map<String, Map<String, Object>>> recheck = classifyAll(targets, remote)
        requireNoConflicts(targets, recheck)
        List<String> disappeared = []
        ['modrinth', 'curseforge'].each { String platform ->
            targets.each { Map<String, Object> target ->
                if (plan[platform][target.key].action == 'skip' &&
                        recheck[platform][target.key].action != 'skip') {
                    disappeared << "${platform} ${target.name}"
                }
            }
        }
        PublicationSupport.requireState(disappeared.empty,
                'publications found during preflight disappeared before upload:\n- ' +
                        disappeared.join('\n- '))
        List<List<String>> results = []
        try {
            ['modrinth', 'curseforge'].each { String platform ->
                targets.each { Map<String, Object> target ->
                    Map<String, Object> state = recheck[platform][target.key]
                    if (state.action == 'skip') {
                        String remoteId = state.remoteIds.find { it } ?: ''
                        results << [target.name, platform, 'skipped', remoteId]
                        return
                    }
                    String remoteId = platform == 'modrinth'
                            ? uploadModrinth(target, modrinthToken)
                            : uploadCurseforge(target, curseforgeUploadToken)
                    results << [target.name, platform, 'uploaded', remoteId]
                }
            }
        } finally {
            if (results) {
                appendSummary('Platform publication', results)
            }
        }

        publishGithub(common.rpName, packVersion, changelog, targets, github)
    }

    private Map<String, Map<String, Map<String, Object>>> classifyAll(
            List<Map<String, Object>> targets,
            Map<String, List<Map<String, Object>>> remote) {
        Map<String, Map<String, Map<String, Object>>> states = [modrinth: [:], curseforge: [:]]
        ['modrinth', 'curseforge'].each { String platform ->
            targets.each { Map<String, Object> target ->
                Map<String, Object> state = PublicationSupport.classify(
                        platform, target, remote[platform])
                states[platform][target.key] = state
            }
        }
        states
    }

    private void requireNoConflicts(
            List<Map<String, Object>> targets,
            Map<String, Map<String, Map<String, Object>>> states) {
        List<String> conflicts = []
        ['modrinth', 'curseforge'].each { String platform ->
            targets.each { Map<String, Object> target ->
                Map<String, Object> state = states[platform][target.key]
                if (state.action == 'conflict') {
                    conflicts << "${platform} ${target.name}: ${state.reason}"
                }
            }
        }
        PublicationSupport.requireState(conflicts.empty,
                'publication preflight found conflicts:\n- ' + conflicts.join('\n- '))
    }

    private List<Map<String, Object>> fetchModrinth(String token) {
        String url = "${apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')}" +
                "/project/${PublicationSupport.MODRINTH_PROJECT_ID}/version"
        Object payload = json(request('GET', url, ['Authorization': token], null, null,
                [200] as Set<Integer>, [token]))
        PublicationSupport.requireState(payload instanceof List,
                'Modrinth version list response is not an array')
        payload.findAll { it instanceof Map } as List<Map<String, Object>>
    }

    private List<Map<String, Object>> fetchCurseforge(String apiKey) {
        String base = apiBase('CURSEFORGE_API_BASE', 'https://api.curseforge.com')
        int index = 0
        int pageSize = 50
        List<Map<String, Object>> files = []
        while (true) {
            String url = "${base}/v1/mods/${PublicationSupport.CURSEFORGE_PROJECT_ID}/files" +
                    "?index=${index}&pageSize=${pageSize}"
            Object payload = json(request('GET', url, ['X-API-Key': apiKey], null, null,
                    [200] as Set<Integer>, [apiKey]))
            PublicationSupport.requireState(payload instanceof Map && payload.data instanceof List,
                    'CurseForge Core API file list response has no data array')
            List<Map<String, Object>> page = payload.data.findAll {
                it instanceof Map
            } as List<Map<String, Object>>
            files.addAll(page)

            Map pagination = payload.pagination instanceof Map ? payload.pagination : [:]
            int resultCount = pagination.resultCount instanceof Number
                    ? (pagination.resultCount as Number).intValue() : page.size()
            Integer totalCount = pagination.totalCount instanceof Number
                    ? (pagination.totalCount as Number).intValue() : null
            PublicationSupport.requireState(resultCount >= 0,
                    'CurseForge Core API returned a negative result count')
            index += resultCount
            if (resultCount == 0 || (totalCount != null && index >= totalCount) ||
                    (totalCount == null && page.size() < pageSize)) {
                break
            }
            PublicationSupport.requireState(index <= 10_000,
                    'CurseForge Core API pagination exceeded its documented limit')
        }
        files
    }

    private String uploadModrinth(Map<String, Object> target, String token) {
        Map metadata = PublicationSupport.modrinthMetadata(target)
        String boundary = "FoggyPaleGarden${UUID.randomUUID().toString().replace('-', '')}"
        byte[] body = PublicationSupport.multipart([
                [name: 'data', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: target.uploadFilename, contentType: 'application/zip',
                 bytes: (target.artifact as File).bytes],
        ], boundary)
        HttpResult response = request('POST',
                "${apiBase('MODRINTH_API_BASE', 'https://api.modrinth.com/v2')}/version",
                ['Authorization': token], body, "multipart/form-data; boundary=${boundary}",
                [200, 201] as Set<Integer>, [token])
        Object payload = json(response)
        PublicationSupport.requireState(payload instanceof Map && payload.id,
                "Modrinth upload for ${target.name} returned no version id")
        payload.id.toString()
    }

    private String uploadCurseforge(Map<String, Object> target, String token) {
        Map metadata = PublicationSupport.curseforgeMetadata(target)
        String boundary = "FoggyPaleGarden${UUID.randomUUID().toString().replace('-', '')}"
        byte[] body = PublicationSupport.multipart([
                [name: 'metadata', filename: null, contentType: 'application/json',
                 bytes: JsonOutput.toJson(metadata).getBytes(StandardCharsets.UTF_8)],
                [name: 'file', filename: target.uploadFilename, contentType: 'application/zip',
                 bytes: (target.artifact as File).bytes],
        ], boundary)
        HttpResult response = request('POST',
                "${apiBase('CURSEFORGE_UPLOAD_API_BASE', 'https://legacy.curseforge.com')}" +
                        "/api/projects/${PublicationSupport.CURSEFORGE_PROJECT_ID}/upload-file",
                ['X-Api-Token': token], body, "multipart/form-data; boundary=${boundary}",
                [200, 201] as Set<Integer>, [token])
        Object payload = json(response)
        Object id = payload instanceof Map ? payload.id : null
        if (id == null && payload instanceof Map && payload.data instanceof Map) {
            id = payload.data.id
        }
        PublicationSupport.requireState(id != null,
                "CurseForge upload for ${target.name} returned no file id")
        id.toString()
    }

    private void publishGithub(String packName,
                               String packVersion,
                               String changelog,
                               List<Map<String, Object>> targets,
                               Map<String, String> github) {
        String token = github.token
        String repository = github.repository
        String sha = github.sha
        String base = github.base
        Map<String, String> headers = githubHeaders(token)
        assertCurrentMain(base, repository, sha, headers, token)

        String encodedVersion = encodePath(packVersion)
        HttpResult tag = request('GET',
                "${base}/repos/${repository}/git/ref/tags/${encodedVersion}", headers,
                null, null, [200, 404] as Set<Integer>, [token])
        if (tag.status == 200) {
            request('PATCH', "${base}/repos/${repository}/git/refs/tags/${encodedVersion}",
                    headers, JsonOutput.toJson([sha: sha, force: true]).getBytes(StandardCharsets.UTF_8),
                    'application/json', [200] as Set<Integer>, [token])
        } else {
            request('POST', "${base}/repos/${repository}/git/refs", headers,
                    JsonOutput.toJson([ref: "refs/tags/${packVersion}", sha: sha])
                            .getBytes(StandardCharsets.UTF_8),
                    'application/json', [201] as Set<Integer>, [token])
        }

        HttpResult existing = request('GET',
                "${base}/repos/${repository}/releases/tags/${encodedVersion}", headers,
                null, null, [200, 404] as Set<Integer>, [token])
        if (existing.status == 200) {
            Object release = json(existing)
            PublicationSupport.requireState(release instanceof Map && release.id,
                    'existing GitHub release returned no id')
            request('DELETE', "${base}/repos/${repository}/releases/${release.id}", headers,
                    null, null, [204] as Set<Integer>, [token])
        }

        Map releaseRequest = [
                tag_name  : packVersion,
                name      : "${packName} ${packVersion}",
                body      : changelog,
                draft     : false,
                prerelease: false,
        ]
        Object release = json(request('POST', "${base}/repos/${repository}/releases", headers,
                JsonOutput.toJson(releaseRequest).getBytes(StandardCharsets.UTF_8),
                'application/json', [201] as Set<Integer>, [token]))
        PublicationSupport.requireState(release instanceof Map && release.id,
                'created GitHub release returned no id')
        String uploadBase = release.upload_url?.toString()?.replaceFirst(/\{.*$/, '')
        PublicationSupport.requireState(uploadBase,
                'created GitHub release returned no upload URL')
        targets.sort { it.artifactName }.each { target ->
            String assetName = URLEncoder.encode(target.artifactName as String,
                    StandardCharsets.UTF_8.name()).replace('+', '%20')
            request('POST',
                    "${uploadBase}?name=${assetName}",
                    headers, (target.artifact as File).bytes, 'application/zip',
                    [201] as Set<Integer>, [token])
        }
        appendRawSummary("## GitHub release\n\nPublished ${packName} ${packVersion} " +
                "with ${targets.size()} assets from ${sha}.\n\n")
    }

    private Map<String, String> validateGithubMain() {
        String token = requireSecret('GITHUB_TOKEN')
        String repository = requireSecret('GITHUB_REPOSITORY')
        String sha = requireSecret('GITHUB_SHA')
        String ref = requireSecret('GITHUB_REF')
        PublicationSupport.requireState(ref == 'refs/heads/main',
                "Publish must be run from main, not ${ref}")
        String base = apiBase('GITHUB_API_BASE', 'https://api.github.com')
        assertCurrentMain(base, repository, sha, githubHeaders(token), token)
        [token: token, repository: repository, sha: sha, base: base]
    }

    private void assertCurrentMain(String base,
                                   String repository,
                                   String sha,
                                   Map<String, String> headers,
                                   String token) {
        Object branch = json(request('GET', "${base}/repos/${repository}/branches/main",
                headers, null, null, [200] as Set<Integer>, [token]))
        PublicationSupport.requireState(branch instanceof Map && branch.commit?.sha == sha,
                "workflow commit ${sha} is not the current main HEAD")
    }

    private Map<String, String> githubHeaders(String token) {
        [
                'Authorization'       : "Bearer ${token}",
                'Accept'              : 'application/vnd.github+json',
                'X-GitHub-Api-Version': '2022-11-28',
        ]
    }

    private HttpResult request(String method,
                               String url,
                               Map<String, String> headers,
                               byte[] body,
                               String contentType,
                               Set<Integer> expectedStatuses,
                               List<String> secrets) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header('User-Agent', USER_AGENT)
                .header('Accept', headers.Accept ?: 'application/json')
        headers.findAll { key, value -> key != 'Accept' }.each { key, value ->
            builder.header(key, value)
        }
        if (contentType) {
            builder.header('Content-Type', contentType)
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)
        HttpResponse<byte[]> response
        try {
            response = client.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofByteArray())
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException("${method} ${url} was interrupted", exception)
        } catch (IOException exception) {
            throw new IllegalStateException("${method} ${url} failed: ${exception.message}", exception)
        }
        String responseBody = new String(response.body(), StandardCharsets.UTF_8)
        if (!expectedStatuses.contains(response.statusCode())) {
            secrets.findAll { it && it.size() >= 8 }.each {
                responseBody = responseBody.replace(it, '[redacted]')
            }
            throw new IllegalStateException(
                    "${method} ${url} returned HTTP ${response.statusCode()}" +
                            (responseBody.trim() ? ": ${responseBody.take(4096)}" : ''))
        }
        new HttpResult(response.statusCode(), responseBody)
    }

    private Object json(HttpResult response) {
        if (!response.body) {
            return [:]
        }
        try {
            new JsonSlurper().parseText(response.body)
        } catch (Exception exception) {
            throw new IllegalStateException('API returned invalid JSON', exception)
        }
    }

    private String requireSecret(String name) {
        String value = System.getenv(name)?.trim()
        PublicationSupport.requireState(value, "required environment variable ${name} is not set")
        value
    }

    private String apiBase(String name, String fallback) {
        (System.getenv(name) ?: fallback).replaceAll('/+$', '')
    }

    private String encodePath(String value) {
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace('+', '%20')
    }

    private List<List<String>> summaryRows(List<Map<String, Object>> targets,
                                           Map<String, Map<String, Map<String, Object>>> states) {
        List<List<String>> rows = []
        targets.each { target ->
            ['modrinth', 'curseforge'].each { platform ->
                Map state = states[platform][target.key]
                rows << [target.name, platform, state.action,
                         state.remoteIds.findAll { it }.join(', ')]
            }
        }
        rows
    }

    private String renderPlan(List<Map<String, Object>> targets,
                              Map<String, Map<String, Map<String, Object>>> states) {
        summaryRows(targets, states).collect { row ->
            "${row[1]} ${row[0]}: ${row[2]}${row[3] ? " (${row[3]})" : ''}"
        }.join('\n')
    }

    private void appendSummary(String heading, List<List<String>> rows) {
        StringBuilder output = new StringBuilder("## ${heading}\n\n")
        output.append('| Target | Platform | Result | Remote ID |\n')
        output.append('| --- | --- | --- | --- |\n')
        rows.each { row ->
            output.append("| ${row[0]} | ${row[1]} | ${row[2]} | ${row[3] ?: '-'} |\n")
        }
        output.append('\n')
        appendRawSummary(output.toString())
    }

    private void appendRawSummary(String text) {
        String path = System.getenv('GITHUB_STEP_SUMMARY')
        if (path) {
            new File(path).append(text, StandardCharsets.UTF_8.name())
        }
    }

    private static final class HttpResult {
        final int status
        final String body

        HttpResult(int status, String body) {
            this.status = status
            this.body = body
        }
    }
}
