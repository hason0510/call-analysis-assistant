package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.event.CanonicalEvent;
import io.hason.callanalysis.domain.event.EventTime;
import io.hason.callanalysis.domain.timeline.CallTimeline;
import io.hason.callanalysis.domain.timeline.RelativeTrack;
import io.hason.callanalysis.infrastructure.file.CallFolderReader;
import io.hason.callanalysis.service.CallLogNormalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dung va in timeline cua mot cuoc goi.
 *
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="--timeline=../ai20k_sample/success/EE129C8F-..."
 */
@Component
public class TimelineCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TimelineCommand.class);
    private static final String OPTION = "timeline";
    private static final int PREVIEW = 12;

    private final CallFolderReader reader;
    private final CallLogNormalizationService service;
    private final ConfigurableApplicationContext context;

    public TimelineCommand(CallFolderReader reader,
                           CallLogNormalizationService service,
                           ConfigurableApplicationContext context) {
        this.reader = reader;
        this.service = service;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        Path folder = Path.of(args.getOptionValues(OPTION).getFirst()).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            log.error("Khong phai thu muc: {}", folder);
            System.exit(SpringApplication.exit(context, () -> 2));
        }

        CallTimeline timeline = service.buildTimeline(
                folder.getFileName().toString(), reader.readAll(folder));

        log.info("Call-ID : {}", timeline.callId());
        log.info("Tong su kien : {}  (main track {}, relative {})",
                timeline.totalEvents(), timeline.mainTrack().size(),
                timeline.totalEvents() - timeline.mainTrack().size());
        log.info("Leg suy ra tu: {}", timeline.legs().derivedFrom());
        log.info("");

        log.info("--- MAIN TRACK ({} su kien dau) ---", PREVIEW);
        timeline.mainTrack().stream().limit(PREVIEW).forEach(e -> log.info("  {}", describe(e)));

        log.info("");
        log.info("--- RELATIVE TRACKS ---");
        for (RelativeTrack track : timeline.relativeTracks()) {
            log.info("  {}", String.format("%-24s leg=%-7s platform=%-8s doTinCay=%s  (%d su kien)",
                    track.fileName(), track.leg(), track.platform(),
                    track.legConfidence(), track.events().size()));
        }

        log.info("");
        log.info("--- LECH DONG HO ---");
        if (timeline.clockOffsets().isEmpty()) {
            log.info("  khong do duoc (thieu cap lenh khop giua client va server)");
        }
        timeline.clockOffsets().forEach(o -> log.info("  {} : {} ms (trung vi tren {} cap){}",
                o.leg(), o.medianMillis(), o.sampleCount(), o.isNegligible() ? "" : "  <-- DANG KE"));

        log.info("");
        log.info("--- GHI CHU ({}) ---", timeline.notes().size());
        timeline.notes().forEach(n -> log.info("  [{}] {}", n.kind(), n.message()));

        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private static String describe(CanonicalEvent e) {
        String time = e.time() instanceof EventTime.Absolute a
                ? a.instant().toString()
                : "+" + ((EventTime.Relative) e.time()).sinceLogStart().toMillis() + "ms";
        return String.format("%-32s %-10s %-7s %-22s %s",
                time, e.source(), e.leg(), e.name(), e.sourceRef().citation());
    }
}
