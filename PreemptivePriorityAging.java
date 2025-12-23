import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preemptive Priority Scheduling with per-process Aging.
 *
 * Input: JSON file(s) passed via args (no interactive input).
 * Expected JSON shape (keys can appear with spaces/newlines):
 * {
 *   "contextSwitch": 1,
 *   "agingInterval": 4,
 *   "processes": [
 *     {"name":"P1","arrival":0,"burst":10,"priority":5},
 *     ...
 *   ]
 * }
 */
public class PreemptivePriorityAging {
    private static final double EPS = 0.01;

    static final class Process {
        final String name;
        final int arrival;
        final int burst;
        final int basePriority;

        int remaining;

        // Your method variables:
        // - newPriority: aged priority (min 1)
        // - newArrival: last time this process entered/returned to ready queue
        int newPriority;
        int newArrival;

        Integer completionTime;

        Process(String name, int arrival, int burst, int priority) {
            this.name = name;
            this.arrival = arrival;
            this.burst = burst;
            this.basePriority = priority;

            this.remaining = burst;
            this.newPriority = priority;
            this.newArrival = 0;
        }
    }

    static final class Input {
        final int contextSwitch;
        final int agingInterval;
        final List<Process> processes;

        Input(int contextSwitch, int agingInterval, List<Process> processes) {
            this.contextSwitch = contextSwitch;
            this.agingInterval = agingInterval;
            this.processes = processes;
        }
    }

    static final class Expected {
        final List<String> executionOrder; // null if not present
        final Double avgWT;                // null if not present
        final Double avgTAT;               // null if not present

        Expected(List<String> executionOrder, Double avgWT, Double avgTAT) {
            this.executionOrder = executionOrder;
            this.avgWT = avgWT;
            this.avgTAT = avgTAT;
        }
    }

    static final class Result {
        final List<String> executionOrder;
        final double avgWT;
        final double avgTAT;

        Result(List<String> executionOrder, double avgWT, double avgTAT) {
            this.executionOrder = executionOrder;
            this.avgWT = avgWT;
            this.avgTAT = avgTAT;
        }
    }

    public static void main(String[] args) throws Exception {
        List<File> files = collectJsonFiles(args);
        if (files.isEmpty()) {
            System.out.println("Usage: java PreemptivePriorityAging <fileOrDir> [fileOrDir...]");
            System.out.println("Note: only JSON inputs are supported (no manual input).");
            return;
        }

        for (File f : files) {
            String json = new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())));
            Input in = parse(json);
            Expected expected = parseExpected(json);
            System.out.println("\n=== " + f.getName() + " ===");
            Result actual = solve(in.processes, in.agingInterval, in.contextSwitch);
            printPassFail(expected, actual);
        }
    }

    // ------------------ Scheduling ------------------
    private static Result solve(List<Process> processes, int agingInterval, int contextSwitchTime) {
        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        ArrayList<Process> ready = new ArrayList<>();
        ArrayList<String> executionOrder = new ArrayList<>();

        int time = 0;
        int completed = 0;
        int nextArrivalIdx = 0;
        boolean hasEverRun = false;

        Process running = null; // executing now
        Process target = null;  // chosen process, still switching
        int csRemaining = 0;

        while (completed < processes.size()) {
            // 1) arrivals at exact time
            while (nextArrivalIdx < processes.size() && processes.get(nextArrivalIdx).arrival == time) {
                Process p = processes.get(nextArrivalIdx);
                p.newPriority = p.basePriority;
                p.newArrival = time;
                ready.add(p);
                nextArrivalIdx++;
            }

            // 2) per-process aging at exact time (ready only)
            if (agingInterval > 0) {
                for (Process p : ready) {
                    int waited = time - p.newArrival;
                    if (waited >= agingInterval && p.newPriority > 1) {
                        p.newPriority = Math.max(1, p.newPriority - 1);
                        p.newArrival = time;
                    }
                }
            }

            // 3) if currently switching to a target, re-check each second and replace if needed
            if (running == null && target != null) {
                Process best = bestOf(ready);
                if (best != null && compare(best, target) < 0) {
                    // old target returns to ready; update its newArrival
                    target.newArrival = time;
                    ready.add(target);

                    ready.remove(best);
                    target = best;
                    csRemaining = contextSwitchTime;
                    record(executionOrder, target.name);
                }
            }

            // 4) preemption check while running
            if (running != null) {
                Process best = bestOf(ready);
                if (best != null && compare(best, running) < 0) {
                    running.newArrival = time;
                    ready.add(running);
                    running = null;

                    ready.remove(best);
                    target = best;
                    csRemaining = contextSwitchTime;
                    record(executionOrder, target.name);
                }
            }

            // 5) if CPU idle and not switching, choose next
            if (running == null && csRemaining == 0 && target == null) {
                Process next = bestOf(ready);
                if (next != null) {
                    ready.remove(next);
                    if (!hasEverRun) {
                        running = next; // first dispatch has no CS
                        hasEverRun = true;
                        record(executionOrder, running.name);
                    } else {
                        target = next;
                        csRemaining = contextSwitchTime;
                        record(executionOrder, target.name);
                    }
                }
            }

            // 6) if CS finished, start target after checks at this time
            if (running == null && csRemaining == 0 && target != null) {
                running = target;
                target = null;
                hasEverRun = true;
            }

            // 7) consume 1 second: run OR CS OR idle
            if (running != null) {
                running.remaining--;
                time++;
                if (running.remaining == 0) {
                    running.completionTime = time;
                    completed++;
                    running = null;
                }
            } else if (csRemaining > 0) {
                csRemaining--;
                time++;
            } else {
                time++;
            }
        }

        return print(processes, executionOrder);
    }

    private static void record(List<String> order, String name) {
        if (order.isEmpty() || !order.get(order.size() - 1).equals(name)) order.add(name);
    }

    private static int compare(Process a, Process b) {
        if (a.newPriority != b.newPriority) return Integer.compare(a.newPriority, b.newPriority);
        if (a.arrival != b.arrival) return Integer.compare(a.arrival, b.arrival);
        return a.name.compareTo(b.name);
    }

    private static Process bestOf(List<Process> ready) {
        Process best = null;
        for (Process p : ready) {
            if (best == null || compare(p, best) < 0) best = p;
        }
        return best;
    }

    private static Result print(List<Process> processes, List<String> order) {
        int totalWT = 0, totalTAT = 0;
        int n = processes.size();

        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        System.out.println("executionOrder: " + order);
        System.out.println("processResults:");
        for (Process p : processes) {
            int tat = p.completionTime - p.arrival;
            int wt = tat - p.burst;
            totalWT += wt;
            totalTAT += tat;
            System.out.println("{name: " + p.name + ", waitingTime: " + wt + ", turnaroundTime: " + tat + "}");
        }
        double avgWT = (double) totalWT / n;
        double avgTAT = (double) totalTAT / n;
        System.out.printf("averageWaitingTime: %.2f%n", avgWT);
        System.out.printf("averageTurnaroundTime: %.2f%n", avgTAT);
        return new Result(order, avgWT, avgTAT);
    }

    private static void printPassFail(Expected expected, Result actual) {
        if (expected.executionOrder == null && expected.avgWT == null && expected.avgTAT == null) {
            System.out.println("testStatus: (no expected output in JSON)");
            return;
        }

        boolean ok = true;
        if (expected.executionOrder != null && !expected.executionOrder.equals(actual.executionOrder)) ok = false;
        if (expected.avgWT != null && Math.abs(expected.avgWT - actual.avgWT) > EPS) ok = false;
        if (expected.avgTAT != null && Math.abs(expected.avgTAT - actual.avgTAT) > EPS) ok = false;

        System.out.println("testStatus: " + (ok ? "PASSED" : "FAILED"));
        if (!ok) {
            if (expected.executionOrder != null) {
                System.out.println("  expected executionOrder: " + expected.executionOrder);
                System.out.println("  actual   executionOrder: " + actual.executionOrder);
            }
            if (expected.avgWT != null) {
                System.out.printf("  expected averageWaitingTime: %.2f%n", expected.avgWT);
                System.out.printf("  actual   averageWaitingTime: %.2f%n", actual.avgWT);
            }
            if (expected.avgTAT != null) {
                System.out.printf("  expected averageTurnaroundTime: %.2f%n", expected.avgTAT);
                System.out.printf("  actual   averageTurnaroundTime: %.2f%n", actual.avgTAT);
            }
        }
    }

    // ------------------ JSON parsing (regex-based) ------------------
    private static Input parse(String json) {
        int contextSwitch = extractInt(json, "contextSwitch", 0);
        int agingInterval = extractInt(json, "agingInterval", 0);

        List<Process> processes = new ArrayList<>();

        // Match each process object inside "processes": [ ... ]
        Pattern procPattern = Pattern.compile(
            "\\{[^\\{\\}]*?\"name\"\\s*:\\s*\"([^\"]+)\"[^\\{\\}]*?\"arrival\"\\s*:\\s*(\\d+)" +
                "[^\\{\\}]*?\"burst\"\\s*:\\s*(\\d+)[^\\{\\}]*?\"priority\"\\s*:\\s*(\\d+)[^\\{\\}]*?\\}"
        );
        Matcher m = procPattern.matcher(json);
        while (m.find()) {
            String name = m.group(1);
            int arrival = Integer.parseInt(m.group(2));
            int burst = Integer.parseInt(m.group(3));
            int pr = Integer.parseInt(m.group(4));
            processes.add(new Process(name, arrival, burst, pr));
        }

        return new Input(contextSwitch, agingInterval, processes);
    }

    private static Expected parseExpected(String json) {
        List<String> order = extractStringArray(json, "executionOrder");
        Double avgWT = extractDouble(json, "averageWaitingTime");
        Double avgTAT = extractDouble(json, "averageTurnaroundTime");
        return new Expected(order, avgWT, avgTAT);
    }

    private static int extractInt(String json, String key, int fallback) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static Double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static List<String> extractStringArray(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) return null;
        String inside = m.group(1).trim();
        if (inside.isEmpty()) return new ArrayList<>();
        String[] parts = inside.split(",");
        ArrayList<String> out = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim().replace("\"", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static List<File> collectJsonFiles(String[] args) {
        List<File> out = new ArrayList<>();
        if (args == null || args.length == 0) return out;

        for (String a : args) {
            File f = new File(a);
            if (!f.exists()) continue;
            if (f.isDirectory()) {
                File[] files = f.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
                if (files != null) {
                    for (File x : files) out.add(x);
                }
            } else if (a.toLowerCase().endsWith(".json")) {
                out.add(f);
            }
        }
        return out;
    }
}

