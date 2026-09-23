package com.prodigalgal.remoteconnectmcp.center;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Discovers published Agent releases and starts the existing canary upgrade flow. */
@Component
public final class AgentReleaseWatcher {
    private static final System.Logger LOG = System.getLogger(AgentReleaseWatcher.class.getName());
    private static final Pattern STABLE_VERSION = Pattern.compile("v(\\d{1,9})\\.(\\d{1,9})\\.(\\d{1,9})");

    private final UpgradeConfig config;
    private final ReleaseCatalogService releases;
    private final UpgradeService upgrades;
    private final AgentRegistry agents;

    public AgentReleaseWatcher(UpgradeConfig config, ReleaseCatalogService releases,
                               UpgradeService upgrades, AgentRegistry agents) {
        this.config = config;
        this.releases = releases;
        this.upgrades = upgrades;
        this.agents = agents;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void check() {
        if (!config.enabled() || !config.automatic()) return;
        try {
            var catalog = releases.list(100, config.includePrerelease());
            if (!catalog.available() || catalog.stale()) return;
            if (catalog.items().isEmpty()) return;
            var candidate = catalog.items().get(0);
            if (candidate.assets().isEmpty()
                    || candidate.assets().stream().anyMatch(asset -> !asset.available() || !asset.checksumAvailable())
                    || upgrades.hasCampaignVersion(candidate.version())) return;
            var targets = selectTargets(agents.listAllMachines(Instant.now()), candidate);
            if (targets.isEmpty()) return;
            upgrades.create(new CreateUpgradeCampaignRequest(candidate.version(), 1, 3,
                    targets, null, true));
            LOG.log(System.Logger.Level.INFO, "Started Agent upgrade for " + candidate.version()
                    + " on " + targets.size() + " machine(s)");
        } catch (RuntimeException exception) {
            // A running/paused campaign or temporary release error must not
            // cause a second campaign or stop the scheduler. The next check
            // retries discovery; the console retains manual resume controls.
            LOG.log(System.Logger.Level.WARNING, "Agent release discovery could not start an upgrade: "
                    + exception.getClass().getSimpleName());
        }
    }

    static List<String> selectTargets(List<MachineView> machines, ReleaseCatalogService.ReleaseView release) {
        var result = new ArrayList<String>();
        // Keep offline machines in the campaign, but let online machines finish
        // the canary and batches before an offline target can hold a wave open.
        for (var machine : machines.stream()
                .sorted(java.util.Comparator.comparing(MachineView::online).reversed()).toList()) {
            var os = machine.os() == null ? "" : machine.os().toLowerCase(Locale.ROOT);
            var arch = machine.arch() == null ? "" : machine.arch().toLowerCase(Locale.ROOT);
            if (release.assets().stream().noneMatch(asset -> asset.os().equals(os) && asset.arch().equals(arch)
                    && asset.available() && asset.checksumAvailable())) continue;
            if (behind(machine.version(), release.version(), release.prerelease())) result.add(machine.id());
        }
        return List.copyOf(result);
    }

    private static boolean behind(String current, String candidate, boolean prerelease) {
        if (candidate.equals(current)) return false;
        if (prerelease) return true;
        var oldVersion = STABLE_VERSION.matcher(current == null ? "" : current);
        var newVersion = STABLE_VERSION.matcher(candidate);
        if (!oldVersion.matches() || !newVersion.matches()) return true;
        for (int index = 1; index <= 3; index++) {
            var oldPart = Integer.parseInt(oldVersion.group(index));
            var newPart = Integer.parseInt(newVersion.group(index));
            if (oldPart != newPart) return oldPart < newPart;
        }
        return false;
    }
}
