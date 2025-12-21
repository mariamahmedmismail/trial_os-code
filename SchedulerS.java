
import java.util.*;

public class SchedulerS {

    static final class Process {
        final String name;
        final int arrival;
        final int burst;
        final int basePriority;

        int remaining;

        // Per your method:
        // - newPriority: priority after updates (aging)
        // - newArrival: the time this process last (re-)entered the ready/waiting queue
        int newPriority;
        int newArrival;

        Integer completionTime; // set when finished

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

    private static int compare(Process a, Process b) {
        if (a.newPriority != b.newPriority) return Integer.compare(a.newPriority, b.newPriority); // lower is better
        if (a.arrival != b.arrival) return Integer.compare(a.arrival, b.arrival); // earlier arrival wins ties
        return a.name.compareTo(b.name);
    }

    private static Process bestOf(List<Process> ready) {
        if (ready.isEmpty()) return null;
        Process best = ready.get(0);
        for (int i = 1; i < ready.size(); i++) {
            Process p = ready.get(i);
            if (compare(p, best) < 0) best = p;
        }
        return best;
    }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        ArrayList<Process> processes = new ArrayList<>();
        ArrayList<Process> ready = new ArrayList<>();
        ArrayList<String> executionOrder = new ArrayList<>(); // dispatch order (not per-tick timeline)

        System.out.print("Number of processes: ");
        int n = sc.nextInt();

        System.out.print("Enter Aging Interval: ");
        int agingInterval = sc.nextInt();

        System.out.print("Enter Context Switch Time: ");
        int contextSwitchTime = sc.nextInt();

        for (int i = 0; i < n; i++) {
            System.out.println("Process " + (i + 1));
            System.out.print("Name: ");
            String name = sc.next();
            System.out.print("Arrival Time: ");
            int arrival = sc.nextInt();
            System.out.print("Burst Time: ");
            int burst = sc.nextInt();
            System.out.print("Priority: ");
            int priority = sc.nextInt();

            processes.add(new Process(name, arrival, burst, priority));
        }

        // Sort by arrival for efficient intake.
        processes.sort(Comparator.comparingInt(p -> p.arrival));

        int time = 0;
        int completed = 0;
        int nextArrivalIdx = 0;

        boolean hasEverRun = false; // to avoid context switch before the very first run at time 0

        Process running = null; // currently executing
        Process target = null;  // the process we're context-switching to (not running yet)
        int csRemaining = 0;    // remaining context switch time units

        while (completed < n) {
            // 1) Add newly arrived processes at this exact time.
            while (nextArrivalIdx < processes.size() && processes.get(nextArrivalIdx).arrival == time) {
                Process p = processes.get(nextArrivalIdx);
                p.newPriority = p.basePriority;
                p.newArrival = time; // initial newarrival = arrival time
                ready.add(p);
                nextArrivalIdx++;
            }

            // 2) Per-process aging (only for ready/waiting queue).
            if (agingInterval > 0) {
                for (Process p : ready) {
                    int waited = time - p.newArrival;
                    if (waited >= agingInterval && p.newPriority > 1) {
                        p.newPriority -= 1;
                        p.newArrival = time;
                    }
                }
            }

            // 3) Re-evaluate the chosen target each second (even during CS).
            //    This matches your rule: "each second check arrivals/aging and re-select if needed".
            if (running == null && target != null) {
                Process bestReady = bestOf(ready);
                if (bestReady != null && compare(bestReady, target) < 0) {
                    // Cancel the current target and restart context switch to the better one.
                    target.newArrival = time; // e.g., P4 newarrival becomes 9 in your example
                    ready.add(target);

                    ready.remove(bestReady);
                    target = bestReady;
                    csRemaining = contextSwitchTime;
                    executionOrder.add(target.name);
                }
            }

            // 4) Preemption check:
            //    preempt if a ready process has higher priority, OR same priority but earlier arrival time.
            if (running != null) {
                Process bestReady = bestOf(ready);
                if (bestReady != null
                    && (bestReady.newPriority < running.newPriority
                        || (bestReady.newPriority == running.newPriority && bestReady.arrival < running.arrival))) {
                    // Preempt running.
                    running.newArrival = time; // time where we last worked on it (it leaves CPU now)
                    ready.add(running);
                    running = null;

                    ready.remove(bestReady);
                    target = bestReady;
                    csRemaining = contextSwitchTime;
                    executionOrder.add(target.name);
                }
            }

            // 5) If CPU is idle and not currently context-switching, pick next process.
            if (running == null && csRemaining == 0 && target == null) {
                Process next = bestOf(ready);
                if (next != null) {
                    ready.remove(next);

                    if (!hasEverRun) {
                        // First ever dispatch: start immediately (no context switch).
                        running = next;
                        executionOrder.add(running.name);
                        hasEverRun = true;
                    } else {
                        target = next;
                        csRemaining = contextSwitchTime;
                        executionOrder.add(target.name);
                    }
                }
            }

            // 5b) If CS is finished and we still have a target, start running it now.
            //      (Runs after the per-second checks at this exact time.)
            if (running == null && csRemaining == 0 && target != null) {
                running = target;
                target = null;
                hasEverRun = true;
            }

            // 6) Execute one second: either run, or context switch, or idle.
            if (running != null) {
                running.remaining--;
                time++;

                if (running.remaining == 0) {
                    running.completionTime = time;
                    completed++;
                    running = null;
                }
                continue;
            }

            if (csRemaining > 0) {
                csRemaining--;
                time++;
                continue;
            }

            // Idle for one second.
            time++;

            // If CPU is idle and we just advanced time, loop continues to process arrivals/aging.
        }

        // Calculate WT & TAT
        int totalWT = 0, totalTAT = 0;

        // Keep original input order in output by sorting by arrival then name for stability.
        processes.sort(Comparator.comparingInt((Process p) -> p.arrival).thenComparing(p -> p.name));

        Map<String, Integer> waitingTimeByName = new LinkedHashMap<>();
        Map<String, Integer> turnaroundTimeByName = new LinkedHashMap<>();

        for (Process p : processes) {
            int turnaround = p.completionTime - p.arrival;
            int waiting = turnaround - p.burst;
            waitingTimeByName.put(p.name, waiting);
            turnaroundTimeByName.put(p.name, turnaround);
            totalWT += waiting;
            totalTAT += turnaround;
        }

        System.out.println("\nexecutionOrder: " + executionOrder);

        System.out.println("\nprocessResults:");
        for (Process p : processes) {
            System.out.println(
                "{name: " + p.name +
                    ", waitingTime: " + waitingTimeByName.get(p.name) +
                    ", turnaroundTime: " + turnaroundTimeByName.get(p.name) +
                    "}"
            );
        }

        System.out.printf("\naverageWaitingTime: %.2f\n", (double) totalWT / n);
        System.out.printf("averageTurnaroundTime: %.2f\n", (double) totalTAT / n);
    }
}