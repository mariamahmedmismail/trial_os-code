import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AG Scheduler (runs ONLY from parsed JSON files).
 *
 * JSON shape supported:
 * {
 *   "contextSwitch": 1,
 *   "processes": [
 *     {"name":"P1","arrival":0,"burst":7,"priority":3,"quantum":4},
 *     ...
 *   ]
 * }
 *
 * If a process has no "quantum" field, the code will default to rrQuantum (or 4).
 */
public class AGScheduler {
    private static final double EPS = 0.01;

    static final class Process {
        final String name;
        final int arrival;
        final int burst;
        final int priority;
        int remaining;

        int quantum;
        final ArrayList<Integer> quantumHistory = new ArrayList<>();

        int completionTime;
        int waitingTime;
        int turnaroundTime;

        Process(String name, int arrival, int burst, int priority, int quantum) {
            this.name = name;
            this.arrival = arrival;
            this.burst = burst;
            this.priority = priority;
            this.remaining = burst;
            this.quantum = quantum;
            if (quantum > 0) quantumHistory.add(quantum);
        }
    }

    static final class Input {
        final int contextSwitch;
        final int rrQuantum;
        final List<Process> processes;

        Input(int contextSwitch, int rrQuantum, List<Process> processes) {
            this.contextSwitch = contextSwitch;
            this.rrQuantum = rrQuantum;
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
            System.out.println("Usage: java AGScheduler <fileOrDir> [fileOrDir...]");
            System.out.println("Note: only JSON inputs are supported (no manual input).");
            return;
        }

        for (File f : files) {
            String json = new String(Files.readAllBytes(Paths.get(f.getAbsolutePath())));
            Input in = parse(json);
            Expected expected = parseExpected(json);
            System.out.println("\n=== " + f.getName() + " ===");
            Result actual = solveAG(in.processes, in.contextSwitch, in.rrQuantum);
            printPassFail(expected, actual);
        }
    }

    // ------------------ AG logic (based on your AGScheduler simulation style) ------------------
    private static Result solveAG(List<Process> processes, int contextSwitch, int defaultQuantum) {
        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        ArrayList<Process> incoming = new ArrayList<>(processes);
        ArrayList<Process> ready = new ArrayList<>();
        ArrayList<Process> finished = new ArrayList<>();
        ArrayList<String> order = new ArrayList<>();

        int time = 0;
        int idx = 0;
        int done = 0;

        Process current = null;

        // normalize quantums
        for (Process p : incoming) {
            if (p.quantum <= 0) {
                p.quantum = (defaultQuantum > 0) ? defaultQuantum : 4;
                if (p.quantumHistory.isEmpty()) p.quantumHistory.add(p.quantum);
            }
        }

        // helper: add arrivals up to time
        while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
            ready.add(incoming.get(idx));
            idx++;
        }

        int timeInQuantum = 0;
        String lastName = "";

        while (done < processes.size()) {
            if (current == null) {
                if (ready.isEmpty()) {
                    time++;
                    while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
                        ready.add(incoming.get(idx));
                        idx++;
                    }
                    continue;
                }

                current = ready.remove(0);
                timeInQuantum = 0;

                if (!lastName.isEmpty() && !lastName.equals(current.name)) {
                    for (int c = 0; c < contextSwitch; c++) {
                        time++;
                        while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
                            ready.add(incoming.get(idx));
                            idx++;
                        }
                    }
                }
            }

            if (order.isEmpty() || !order.get(order.size() - 1).equals(current.name)) {
                order.add(current.name);
            }
            lastName = current.name;

            // execute 1 unit
            current.remaining--;
            timeInQuantum++;
            time++;

            while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
                ready.add(incoming.get(idx));
                idx++;
            }

            if (current.remaining == 0) {
                current.completionTime = time;
                current.turnaroundTime = current.completionTime - current.arrival;
                current.waitingTime = current.turnaroundTime - current.burst;
                current.quantum = 0;
                current.quantumHistory.add(0);
                finished.add(current);
                done++;
                current = null;
                continue;
            }

            // AG phases (25%, then 50%)
            int q = current.quantum;
            int q1 = (int) Math.ceil(q * 0.25);
            int q2 = q1 + (int) Math.ceil(q * 0.25);

            boolean switched = false;

            // at 50%: shortest remaining can preempt
            if (timeInQuantum == q2) {
                Process shortest = null;
                for (Process p : ready) {
                    if (shortest == null || p.remaining < shortest.remaining) shortest = p;
                }
                if (shortest != null && shortest.remaining < current.remaining) {
                    int remainingQ = q - timeInQuantum;
                    current.quantum += remainingQ;
                    current.quantumHistory.add(current.quantum);
                    ready.add(current);

                    for (int c = 0; c < contextSwitch; c++) {
                        time++;
                        while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
                            ready.add(incoming.get(idx));
                            idx++;
                        }
                    }

                    ready.remove(shortest);
                    current = shortest;
                    timeInQuantum = 0;
                    switched = true;
                }
            }

            // at 25%: higher priority can preempt
            if (!switched && timeInQuantum == q1) {
                Process bestPrio = null;
                for (Process p : ready) {
                    if (bestPrio == null || p.priority < bestPrio.priority) bestPrio = p;
                }
                if (bestPrio != null && bestPrio.priority < current.priority) {
                    int remainingQ = q - timeInQuantum;
                    current.quantum += (int) Math.ceil(remainingQ / 2.0);
                    current.quantumHistory.add(current.quantum);
                    ready.add(current);

                    for (int c = 0; c < contextSwitch; c++) {
                        time++;
                        while (idx < incoming.size() && incoming.get(idx).arrival <= time) {
                            ready.add(incoming.get(idx));
                            idx++;
                        }
                    }

                    ready.remove(bestPrio);
                    current = bestPrio;
                    timeInQuantum = 0;
                    switched = true;
                }
            }

            // quantum exhausted: +2 then round-robin
            if (!switched && timeInQuantum >= q) {
                current.quantum += 2;
                current.quantumHistory.add(current.quantum);
                ready.add(current);
                current = null;
            }
        }

        // print
        System.out.println("executionOrder: " + order);
        System.out.println("processResults:");

        finished.sort(Comparator.comparing(p -> p.name));
        double totalWT = 0, totalTAT = 0;
        for (Process p : finished) {
            totalWT += p.waitingTime;
            totalTAT += p.turnaroundTime;
            System.out.println("{name: " + p.name + ", waitingTime: " + p.waitingTime + ", turnaroundTime: " + p.turnaroundTime + "}");
        }
        System.out.printf("averageWaitingTime: %.2f%n", totalWT / finished.size());
        System.out.printf("averageTurnaroundTime: %.2f%n", totalTAT / finished.size());

        System.out.println("quantumHistory:");
        for (Process p : finished) {
            System.out.println(p.name + ": " + p.quantumHistory);
        }

        return new Result(order, totalWT / finished.size(), totalTAT / finished.size());
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
        int rrQuantum = extractInt(json, "rrQuantum", 4);
        if (rrQuantum == 0) rrQuantum = extractInt(json, "quantum", 4);
        if (rrQuantum == 0) rrQuantum = 4;

        List<Process> processes = new ArrayList<>();

        Pattern procPattern = Pattern.compile(
            "\\{[^\\{\\}]*?\"name\"\\s*:\\s*\"([^\"]+)\"[^\\{\\}]*?\"arrival\"\\s*:\\s*(\\d+)" +
                "[^\\{\\}]*?\"burst\"\\s*:\\s*(\\d+)[^\\{\\}]*?\"priority\"\\s*:\\s*(\\d+)" +
                "(?:[^\\{\\}]*?\"quantum\"\\s*:\\s*(\\d+))?[^\\{\\}]*?\\}"
        );
        Matcher m = procPattern.matcher(json);
        while (m.find()) {
            String name = m.group(1);
            int arrival = Integer.parseInt(m.group(2));
            int burst = Integer.parseInt(m.group(3));
            int pr = Integer.parseInt(m.group(4));
            int q = (m.group(5) != null) ? Integer.parseInt(m.group(5)) : 0;
            processes.add(new Process(name, arrival, burst, pr, q));
        }

        return new Input(contextSwitch, rrQuantum, processes);
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

