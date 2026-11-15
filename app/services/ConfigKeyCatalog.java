package services;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every Config DB key the code reads, the keys it has stopped reading, and the strings in the code that only look like
 * keys (JF-440). A line is an exact key or a pattern whose {@code *} stands for a name the code fills in at run time
 * (a provider, agent, plan or model id, which may itself hold dots).
 *
 * <p>A stored row no {@link #READ} line matches is one this build does not read, which {@code /quality}'s dead config
 * rows pass reports. {@code DefaultConfigJob} deletes every {@link #RETIRED} row at boot.
 * {@code ConfigKeyCatalogConformanceTest} holds the lists to the code: a key the code names must be on one of them,
 * and a {@code READ} line the code no longer names moves to {@code RETIRED}.
 */
public final class ConfigKeyCatalog {

    private ConfigKeyCatalog() {}

    static final String READ = """
            agent.*.chromeBridge.*
            agent.*.shell.allowGlobalPaths
            agent.*.shell.bypassAllowlist
            alerts.delivery
            app.timezone
            auth.admin.passwordHash
            auth.credentialVersion
            auth.internal.apiToken
            auth.revokedSessions
            browser.engine
            caption.model
            caption.provider
            chat.compaction.checkSummary
            chat.compaction.factsBudgetPercent
            chat.compaction.factsCeilingPercent
            chat.compaction.forcedKeepPercent
            chat.compaction.forcedMinSummaryTokens
            chat.compaction.keepPercent
            chat.compaction.minSummaryTokens
            chat.compaction.onRowLimit
            chat.compaction.summaryBudgetPercent
            chat.compaction.summaryBudgetTokens
            chat.compaction.toolResultChars
            chat.compaction.triggerPercent
            chat.compaction.triggerTokens
            chat.compaction.verbatimUserTokens
            chat.compactionMaxTokens
            chat.compactionReserveTokens
            chat.compactionReserveTokensFloor
            chat.compression.firstViewMaxTokens
            chat.compression.minTokens
            chat.historyLimitFloorApplied
            chat.maxContextMessages
            chat.maxToolRounds
            chat.pruneToolResults
            chat.pruneToolResultsEveryTurns
            chat.pruneToolResultsMinChars
            chat.pruneToolResultsProtectRecent
            chat.stream.token_coalesce_chars
            chat.summarySegmentsApplied
            chat.truncateToolResultsKeepHeadChars
            chat.truncateToolResultsKeepTailChars
            chat.truncateToolResultsMinChars
            chat.truncateToolResultsPreserveRecent
            chat.userMessagePreviewPercent
            chromeBridge.find.model
            chromeBridge.find.provider
            conversation.queue.idleEvictionMs
            db.backup.dir
            db.backup.retention
            db.backup.schedule
            decision.baseten.apiKey
            decision.jev.apiKey
            decision.laya.apiKey
            decision.laya.baseUrl
            decision.ollama.baseUrl
            decision.ollama.keepAlive
            dispatcher.llm.maxRequests
            dispatcher.llm.maxRequestsPerHost
            factory.home
            groupChat.historyLimit
            imagegen.*.model
            imagegen.imageSize
            imagegen.local.*
            imagegen.provider
            imagegen.timeoutSeconds
            jtokkit.safetyMultiplier.*
            latency.metrics.retentionDays
            llm.breaker.consecutive-failures
            llm.breaker.failure-rate
            llm.breaker.first-chunk-seconds
            llm.breaker.half-open-probes
            llm.breaker.min-calls
            llm.breaker.slow-rate
            llm.breaker.stall-abort-seconds
            llm.breaker.stall-seconds
            llm.breaker.wait-seconds
            llm.breaker.window
            llm.primaryProvider
            logging.level.*
            logs.retentionDays
            memory.autocapture.dedup.containmentThreshold
            memory.autocapture.dedup.cosineThreshold
            memory.autocapture.dedup.minLengthRatio
            memory.autocapture.dedup.retrievalLimit
            memory.autocapture.dedup.scanLimit
            memory.autocapture.dedup.semantic.enabled
            memory.autocapture.dedup.semanticLimit
            memory.autocapture.dedup.threshold
            memory.autocapture.gate.minChars
            memory.autocapture.lockWaitSeconds
            memory.autocapture.maxCandidates
            memory.autocapture.maxPerTurn
            memory.autocapture.maxTokens
            memory.consolidation.enabled
            memory.consolidation.maxTokens
            memory.consolidation.shortlist.maxPerCandidate
            memory.consolidation.shortlist.minContainment
            memory.consolidation.shortlist.minJaccard
            memory.coreload.enabled
            memory.coreload.maxCount
            memory.coreload.minImportance
            memory.decay.enabled
            memory.decay.floor
            memory.decay.halfLifeDays
            memory.decay.importanceBoost
            memory.forget.match.containmentThreshold
            memory.forget.match.cosineThreshold
            memory.forget.match.threshold
            memory.forget.nearMiss.minContainment
            memory.graph.firmCorroborationThreshold
            memory.jpa.vector.backfilledForModel
            memory.jpa.vector.dimensions
            memory.jpa.vector.enabled
            memory.jpa.vector.keepWarm
            memory.jpa.vector.model
            memory.jpa.vector.provider
            memory.jpa.vector.queryPrefix
            memory.recall.clusterMax
            memory.recall.importanceWeight
            memory.recall.limit
            memory.recall.minCosine
            memory.recall.minScoreRatio
            memory.recall.relevanceWeight
            memory.recall.rrfK
            memory.recall.toolMaxLimit
            memory.rerank.enabled
            memory.rerank.maxTokens
            memory.rerank.model
            memory.rerank.provider
            memory.search.maxHits
            ocr.pdf.strategy
            ocr.tesseract.enabled
            ocr.tesseract.languages
            ocr.tesseract.timeout
            onboarding.tourMaxStep
            otel.enabled
            otel.exporter.endpoint
            otel.exporter.protocol
            otel.exporter.secretHeaders
            otel.metrics.interval.seconds
            otel.service.name
            otel.traces.sampler.ratio
            pricing.refresh.enabled
            printer.default.host
            printer.default.name
            printer.default.option.*
            printer.default.port
            printer.default.protocol
            provider.*.apiKey
            provider.*.baseUrl
            provider.*.enabled
            provider.*.keepAlive
            provider.*.leaderboardUrl
            provider.*.local
            provider.*.models
            provider.*.paymentModality
            provider.*.subscriptionMonthlyUsd
            provider.*.useNativeApi
            provider.loadtest-mock.maxConcurrency
            provider.loadtest-mock.maxTurns
            provider.loadtest-mock.port
            router.*.models
            router.budget.downshiftAt
            router.budget.exhaustedAt
            router.classifier.jev.minConfidence
            router.classifier.model
            router.classifier.provider
            router.classifier.timeoutSeconds
            router.preferPrepaid
            router.speculate
            scanner.malwarebazaar.authKey
            scanner.malwarebazaar.enabled
            scanner.malwarebazaar.timeoutMs
            scanner.malwarebazaar.url
            scanner.metadefender.apiKey
            scanner.metadefender.enabled
            scanner.metadefender.timeoutMs
            scanner.metadefender.url
            scanner.virustotal.apiKey
            scanner.virustotal.enabled
            scanner.virustotal.timeoutMs
            scanner.virustotal.url
            script.nodeHeapMb
            search.*.apiKey
            search.*.baseUrl
            search.*.enabled
            search.*.priority
            search.lucene.analyzerGeneration
            search.perplexity.recencyFilter
            shell.allowlist
            shell.backgroundLifetimeHours
            shell.defaultTimeoutSeconds
            shell.maxOutputBytes
            shell.maxTimeoutSeconds
            shell.sandbox
            shell.sandbox.homeReads
            shell.sandbox.network
            skillsPromotion.batchSizeKb
            skillsPromotion.model
            skillsPromotion.provider
            skillsPromotion.timeoutSeconds
            subagent.acp.command
            subagent.acp.customCommands
            subagent.acp.harness
            subagent.acp.mode
            subagent.acp.modelId
            subagent.acp.modelProvider
            subagent.acp.permissionArgs
            subagent.acp.sandbox
            subagent.acp.workdir
            subagent.defaultRunTimeoutSeconds
            subagent.defaultYieldTimeoutSeconds
            subagent.maxChildrenPerParent
            subagent.maxDepth
            subagent.maxHandOffs
            subagent.maxWallClockSeconds
            subagent.modelId
            subagent.modelProvider
            subagent.parentContextMaxTokens
            tailscale.funnel.enabled
            tailscale.funnel.port
            tasks.defaultTimezone
            tasks.fireIdleSeconds
            tasks.fireMaxDurationSeconds
            tasks.pauseAfterFailedOccurrences
            tasks.retentionDays
            telegram.ackReaction
            telegram.actions.*
            telegram.approval.timeout-seconds
            telegram.inbound.coalesce-threshold
            telegram.inbound.coalesce-window-ms
            telegram.inbound.forward-coalesce-window-ms
            telegram.keyboardScope
            telegram.linkPreview
            telegram.mentionPatterns
            telegram.notifier.cooldownMs
            telegram.notifier.policy
            telegram.reactions.notify
            telegram.replyTo.mode
            tool.approval.offChannelPolicy
            tools.maskTokenShapes
            transcription.asr.local.*
            transcription.diarization.emotionModel
            transcription.diarization.local.*
            transcription.diarization.model
            transcription.diarization.provider
            transcription.localModel
            transcription.model
            transcription.provider
            tts.*.model
            tts.*.refAudio
            tts.*.voice
            tts.engine
            tts.jvm.downloadTimeoutSeconds
            tts.jvm.numThreads
            tts.local.*
            tts.maxChars
            upload.maxAudioBytes
            upload.maxFileBytes
            upload.maxFiles
            upload.maxImageBytes
            verification.enabled
            verification.skipTools
            video.maxInlineMb
            video.model
            video.provider
            video.sampleFrames
            video.secondsPerFrame
            video.textSummaryMaxFrames
            videogen.bfl.model
            videogen.cloud.model
            videogen.local.*
            videogen.maxJobMinutes
            videogen.provider
            visual.enabled
            voice.endpoint.baseSilenceMs
            voice.endpoint.maxSilenceMs
            voice.endpoint.minUtteranceMs
            voice.endpoint.semanticHold
            voice.endpoint.speechStartMs
            voice.partials.enabled
            voice.partials.intervalMs
            voice.tts.maxRunOnChars
            voice.vad.downloadTimeoutSeconds
            voice.vad.modelUrl
            voice.vad.threshold
            web_fetch.allowlist
            web_fetch.max-escalations-per-minute
            web_scrape.concurrency
            web_scrape.impersonate.*
            web_scrape.job.max-concurrent
            web_scrape.job.max-minutes
            web_scrape.job.max-pages
            web_scrape.language
            web_scrape.max-depth
            web_scrape.max-escalations
            web_scrape.max-pages
            web_scrape.max-sitemap-documents
            web_scrape.max-sitemap-urls
            web_scrape.proxy.dataimpulse.*.login
            web_scrape.proxy.dataimpulse.*.password
            web_scrape.proxy.dataimpulse.plan
            web_scrape.proxy.dataimpulse.targeting
            web_scrape.proxy.enabled
            web_scrape.proxy.password
            web_scrape.proxy.url
            web_scrape.proxy.username
            web_scrape.respect-robots
            web_scrape.seed-from-sitemap
            web_scrape.stealth.*
            web_scrape.timeout-seconds
            whatsapp.acceptedMessages.retentionDays
            """;

    /** Exact keys only: a pattern here would delete every row it matched at boot. */
    static final String RETIRED = """
            chat.compaction.onToolRound
            decision.baseten.models
            decision.laya.models
            decision.ollama.models
            transcription.asr.sidecar
            transcription.diarization.backend
            transcription.diarization.model.fallback
            transcription.diarization.provider.fallback
            transcription.language
            """;

    /** Config-shaped strings that are no Config DB key: application.conf keys, bus events, sidecar prefixes, renamed keys. */
    static final String NOT_CONFIG = """
            agent.id
            app.json
            apps.invoke.limit.ceiling
            apps.invoke.limit.default
            apps.invoke.limit.windowSeconds
            auth.abuse.ch
            auth.login.rate-limit.max-failures
            auth.login.rate-limit.window-seconds
            auth.password.breach-check.enabled
            auth.password.breach-check.timeout-ms
            browser.jev.apiKey
            conversation.busy
            conversation.compacted
            conversation.compaction
            conversation.updated
            db.driver
            db.pass
            db.url
            db.user
            imagegen.local
            io.opentelemetry.javaagent.bootstrap.AgentClassLoader
            llm.retry.backoff-percent
            ocr.tesseract.path
            ollama.com
            ollama.keepAlive
            otel.semconv-stability.opt-in
            playwright.cli.dir
            playwright.nodejs.path
            router.classifier
            slack.com
            slack.webhook
            telegram.webhook.max-body-bytes
            telegram.webhook.rate-limit.max
            telegram.webhook.rate-limit.window-seconds
            telegram.webhook.trusted-proxy
            transcription.asr.local
            transcription.diarization.local
            tts.local
            videogen.local
            voice.wav
            web_fetch.max-body-bytes
            web_scrape.impersonate
            web_scrape.stealth
            whatsapp.webhook
            """;

    private static final List<String> READ_LINES = lines(READ);
    private static final Set<String> READ_EXACT = READ_LINES.stream()
            .filter(l -> !l.contains("*"))
            .collect(Collectors.toUnmodifiableSet());
    private static final Pattern READ_PATTERNS = Pattern.compile(READ_LINES.stream()
            .filter(l -> l.contains("*"))
            .map(ConfigKeyCatalog::regexOf)
            .collect(Collectors.joining("|")));

    /** Whether some code in this build reads {@code key}. */
    public static boolean isRead(String key) {
        return READ_EXACT.contains(key) || READ_PATTERNS.matcher(key).matches();
    }

    public static List<String> readLines() {
        return READ_LINES;
    }

    public static List<String> retired() {
        return lines(RETIRED);
    }

    public static List<String> notConfig() {
        return lines(NOT_CONFIG);
    }

    /** The regex a catalog line stands for: {@code *} is any non-empty run, everything else literal. */
    public static String regexOf(String line) {
        var out = new StringBuilder();
        var parts = line.split("\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append(".+");
            if (!parts[i].isEmpty()) out.append(Pattern.quote(parts[i]));
        }
        return out.toString();
    }

    private static List<String> lines(String block) {
        var seen = new HashSet<String>();
        return block.lines().map(String::strip).filter(l -> !l.isEmpty() && seen.add(l)).toList();
    }
}
