package com.prodigalgal.remoteconnectmcp.updater;

/** One-shot entrypoint for replacing Agent bundles outside the resident process. */
public final class UpdaterApplication {
    private UpdaterApplication() {
    }

    public static void main(String[] args) {
        if (args.length == 2 && "--apply-update".equals(args[0])) {
            System.exit(AgentUpgradeHelper.run(args[1]));
            return;
        }
        System.err.println("usage: rcm-updater --apply-update <helper-config>");
        System.exit(2);
    }
}
