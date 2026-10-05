package com.prodigalgal.remoteconnectmcp.center;

import com.prodigalgal.remoteconnectmcp.protocol.UpgradeComponentPlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ReleaseManifestService manifests;

    public AgentReleaseWatcher(UpgradeConfig config, ReleaseCatalogService releases,
                               UpgradeService upgrades, AgentRegistry agents, ReleaseManifestService manifests) {
        this.config = config;
        this.releases = releases;
        this.upgrades = upgrades;
        this.agents = agents;
        this.manifests = manifests;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void check() {
        if (!config.enabled() || !config.automatic()) return;
        try {
            if (upgrades.hasActiveCampaign()) return;
            var catalog = releases.list(100, config.includePrerelease());
            if (!catalog.available() || catalog.stale()) return;
            if (catalog.items().isEmpty()) return;
            var candidate = selectRelease(catalog.items(), config.includePrerelease());
            if (candidate == null) return;
            if (candidate.assets().isEmpty()
                    || candidate.assets().stream().anyMatch(asset -> !asset.available() || !asset.checksumAvailable())) return;
            var plans = manifests.allComponents(candidate.version());
            var proof = upgrades.completedComponents(candidate.version());
            var targets = selectTargets(agents.listAllMachines(Instant.now()), candidate,
                    config.excludedMachineIds(), plans, proof);
            if (targets.isEmpty()) return;
            upgrades.create(new CreateUpgradeCampaignRequest(candidate.version(), 1, 3,
                    targets, null, false, plans));
            LOG.log(System.Logger.Level.INFO, "Started Agent upgrade for " + candidate.version()
                    + " on " + targets.size() + " machine(s)");
        } catch (RuntimeException exception) {
            // A concurrent campaign or temporary release error must not stop
            // discovery. The next check retries after the state changes.
            var message = com.prodigalgal.remoteconnectmcp.protocol.SensitiveValueRedactor.redact(
                    String.valueOf(exception.getMessage()));
            LOG.log(System.Logger.Level.WARNING, "Agent release discovery could not start an upgrade: "
                    + exception.getClass().getSimpleName() + ": " + message.substring(0, Math.min(512, message.length())));
        }
    }

    static List<String> selectTargets(List<MachineView> machines, ReleaseCatalogService.ReleaseView release) {
        return selectTargets(machines, release, java.util.Set.of());
    }

    static List<String> selectTargets(List<MachineView> machines, ReleaseCatalogService.ReleaseView release,
                                      java.util.Set<String> excludedMachineIds) {
        return selectTargets(machines, release, excludedMachineIds, Map.of(), Map.of());
    }

    static List<String> selectTargets(List<MachineView> machines, ReleaseCatalogService.ReleaseView release,
            Set<String> excludedMachineIds, Map<String, List<UpgradeComponentPlan>> plans,
            Map<String, List<UpgradeComponentPlan>> proof) {
        var result = new ArrayList<String>();
        for (var machine : machines.stream()
                .filter(MachineView::online)
                .filter(machine -> !excludedMachineIds.contains(machine.id()))
                .sorted(Comparator.comparing(MachineView::id)).toList()) {
            var os = machine.os() == null ? "" : machine.os().toLowerCase(Locale.ROOT);
            var arch = machine.arch() == null ? "" : machine.arch().toLowerCase(Locale.ROOT);
            if (release.assets().stream().noneMatch(asset -> asset.os().equals(os) && asset.arch().equals(arch)
                    && asset.available() && asset.checksumAvailable())) continue;
            var missingComponents = release.version().equals(machine.version())
                    && UpgradeService.componentsForMachine(plans, machine).stream()
                    .anyMatch(plan -> !proof.getOrDefault(machine.id(), List.of()).contains(plan));
            if (behind(machine.version(), release.version(), release.prerelease()) || missingComponents) result.add(machine.id());
        }
        return List.copyOf(result);
    }

    /** A late recovery publication must not hide a newer stable release. */
    static ReleaseCatalogService.ReleaseView selectRelease(List<ReleaseCatalogService.ReleaseView> releases,
                                                            boolean includePrerelease) {
        if (includePrerelease) return releases.isEmpty() ? null : releases.get(0);
        return releases.stream().filter(release -> !release.prerelease()
                        && STABLE_VERSION.matcher(release.version()).matches())
                .max((left, right) -> compareStableVersions(left.version(), right.version())).orElse(null);
    }

    private static int compareStableVersions(String left, String right) {
        var a = STABLE_VERSION.matcher(left);
        var b = STABLE_VERSION.matcher(right);
        if (!a.matches() || !b.matches()) throw new IllegalArgumentException("stable release version is invalid");
        for (int index = 1; index <= 3; index++) {
            var difference = Integer.compare(Integer.parseInt(a.group(index)), Integer.parseInt(b.group(index)));
            if (difference != 0) return difference;
        }
        return 0;
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
