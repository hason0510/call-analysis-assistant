package io.hason.callanalysis.cli;

import io.hason.callanalysis.domain.signaling.SignalingFetch;
import io.hason.callanalysis.service.port.SignalingSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Doc nhanh signaling cua mot cuoc goi tu Elasticsearch local.
 *
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--fetch-call=DE7DD314-F432-45CB-BCB4-AE9103CC0919"
 */
@Component
public class FetchCallCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FetchCallCommand.class);
    private static final String OPTION = "fetch-call";

    private final SignalingSource signalingSource;
    private final ConfigurableApplicationContext context;

    public FetchCallCommand(SignalingSource signalingSource, ConfigurableApplicationContext context) {
        this.signalingSource = signalingSource;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        String callId = args.getOptionValues(OPTION).getFirst();
        SignalingFetch fetch = signalingSource.fetchByCallId(callId);

        log.info("Call-ID   : {}", fetch.callId());
        log.info("So event  : {}", fetch.records().size());
        log.info("truncated : {} (returned {}/{}, thieu {})",
                fetch.truncated(), fetch.returned(), fetch.totalMatching(), fetch.missingCount());

        fetch.records().stream().limit(8).forEach(r -> log.info(
                "  #{} {} {} {} user={} isp={} latencyMs={}",
                r.ordinal(), r.timestamp(), r.level(), r.cmd(), r.appUserId(), r.isp(), r.latencyMs()));
        if (fetch.records().size() > 8) {
            log.info("  ... con {} event nua", fetch.records().size() - 8);
        }

        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
