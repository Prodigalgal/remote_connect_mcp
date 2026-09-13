package center

import (
	"fmt"
	"sort"
	"strings"
	"time"
)

// MetricsText returns a small Prometheus-compatible snapshot.  It deliberately
// exposes counts and timings only; command text, tokens, paths and task output
// never appear in metrics.
func (s *Store) MetricsText(now time.Time) string {
	now = now.UTC()
	s.mu.Lock()
	defer s.mu.Unlock()

	online := 0
	for _, machine := range s.state.Machines {
		if now.Sub(machine.LastSeen) <= 45*time.Second {
			online++
		}
	}
	taskCounts := map[string]int{}
	outputBytes := int64(0)
	for _, task := range s.state.Tasks {
		taskCounts[task.Status]++
		outputBytes += task.OutputBytes
	}
	upgradeCounts := map[string]int{}
	for _, upgrade := range s.state.Upgrades {
		upgradeCounts[upgrade.Status]++
	}

	var b strings.Builder
	b.WriteString("# HELP remote_connect_mcp_machines_total Registered machines.\n")
	b.WriteString("# TYPE remote_connect_mcp_machines_total gauge\n")
	fmt.Fprintf(&b, "remote_connect_mcp_machines_total %d\n", len(s.state.Machines))
	b.WriteString("# HELP remote_connect_mcp_machines_online Online machines according to the heartbeat window.\n")
	b.WriteString("# TYPE remote_connect_mcp_machines_online gauge\n")
	fmt.Fprintf(&b, "remote_connect_mcp_machines_online %d\n", online)
	b.WriteString("# HELP remote_connect_mcp_tasks_total Persisted tasks by status.\n")
	b.WriteString("# TYPE remote_connect_mcp_tasks_total gauge\n")
	statuses := make([]string, 0, len(taskCounts))
	for status := range taskCounts {
		statuses = append(statuses, status)
	}
	sort.Strings(statuses)
	for _, status := range statuses {
		fmt.Fprintf(&b, "remote_connect_mcp_tasks_total{status=%q} %d\n", status, taskCounts[status])
	}
	b.WriteString("# HELP remote_connect_mcp_task_output_bytes_total Persisted task output bytes.\n")
	b.WriteString("# TYPE remote_connect_mcp_task_output_bytes_total counter\n")
	fmt.Fprintf(&b, "remote_connect_mcp_task_output_bytes_total %d\n", outputBytes)
	b.WriteString("# HELP remote_connect_mcp_upgrades_total Upgrade campaigns by status.\n")
	b.WriteString("# TYPE remote_connect_mcp_upgrades_total gauge\n")
	upgradeStatuses := make([]string, 0, len(upgradeCounts))
	for status := range upgradeCounts {
		upgradeStatuses = append(upgradeStatuses, status)
	}
	sort.Strings(upgradeStatuses)
	for _, status := range upgradeStatuses {
		fmt.Fprintf(&b, "remote_connect_mcp_upgrades_total{status=%q} %d\n", status, upgradeCounts[status])
	}
	return b.String()
}
