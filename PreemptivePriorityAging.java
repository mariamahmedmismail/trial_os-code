import java.util.*;
 
class Process {
    String name;
    int arrival;
    int burst;
    int remaining;
 
    int finishTime;       // completion time
    int waitingTime;
    int turnaroundTime;
 
    // This field is the "newPriority" (priority after aging updates)
    int priority;
 
    // This field is the "newarrival" (last time it (re-)entered ready/waiting queue)
    int lastReadyTime;
 
    Process(String name, int arrival, int burst, int priority) {
        this.name = name;
        this.arrival = arrival;
        this.burst = burst;
        this.remaining = burst;
 
        this.priority = priority;
        this.lastReadyTime = 0; // will be set to arrival when it arrives
    }
}
 
public class PreemptivePriorityAging {
 
    private static void addArrivalsAtTime(List<Process> all, int[] idxRef, int time, List<Process> ready) {
        int i = idxRef[0];
        while (i < all.size() && all.get(i).arrival == time) {
            Process p = all.get(i);
            p.lastReadyTime = time; // newarrival = arrival time
            ready.add(p);
            i++;
        }
        idxRef[0] = i;
    }
 
    // Per-process aging: if (time - newarrival) >= agingInterval, priority-- (min 1), then newarrival=time
    private static void applyAging(List<Process> ready, int time, int agingInterval) {
        if (agingInterval <= 0) return;
 
        for (Process p : ready) {
            int waited = time - p.lastReadyTime;
            if (waited >= agingInterval && p.priority > 1) {
                p.priority -= 1;           // lower number => higher priority
                p.lastReadyTime = time;    // reset the per-process aging clock
            }
        }
    }
 
    // Choose best from ready:
    // - lower priority number first
    // - if equal: earlier original arrival time first
    // - then name for deterministic tie-break
    private static Process pickBest(List<Process> ready) {
        if (ready.isEmpty()) return null;
 
        Process best = ready.get(0);
        for (int i = 1; i < ready.size(); i++) {
            Process p = ready.get(i);
            if (p.priority < best.priority
                || (p.priority == best.priority && p.arrival < best.arrival)
                || (p.priority == best.priority && p.arrival == best.arrival && p.name.compareTo(best.name) < 0)) {
                best = p;
            }
        }
        return best;
    }
 
    private static boolean isBetter(Process a, Process b) {
        if (b == null) return true;
        if (a.priority != b.priority) return a.priority < b.priority;
        if (a.arrival != b.arrival) return a.arrival < b.arrival;
        return a.name.compareTo(b.name) < 0;
    }
 
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
 
        ArrayList<Process> processes = new ArrayList<>();
        ArrayList<Process> readyQueue = new ArrayList<>();
        ArrayList<String> executionOrder = new ArrayList<>(); // dispatch order (NO "CS" entries)
 
        System.out.print("Number of processes: ");
        int n = sc.nextInt();
 
        System.out.print("Enter Aging Interval: ");
        int agingInterval = sc.nextInt();
 
        System.out.print("Enter Context Switch Time: ");
        int contextSwitchTime = sc.nextInt();
 
        for (int i = 0; i < n; i++) {
            System.out.println("\nProcess " + (i + 1));
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
 
        // Sort processes by arrival once; intake uses an index.
        processes.sort(Comparator.<Process>comparingInt(p -> p.arrival).thenComparing(p -> p.name));
 
        int time = 0;
        int completed = 0;
        int[] nextIdx = new int[] {0};
 
        Process running = null;      // currently executing
        Process switchingTo = null;  // selected process, waiting for CS to finish
        int csLeft = 0;              // remaining CS time units
 
        boolean startedOnce = false; // first dispatch has no CS (matches your example behavior)
 
        while (completed < n) {
            // (A) arrivals at this time
            addArrivalsAtTime(processes, nextIdx, time, readyQueue);
 
            // (B) aging per process at this time (ready/waiting only)
            applyAging(readyQueue, time, agingInterval);
 
            // (C) if we are in/after a context switch, we still re-check each second:
            //     "if a new process has higher priority OR a ready process ages to be better/equal"
            if (running == null && switchingTo != null) {
                Process bestReady = pickBest(readyQueue);
                if (bestReady != null && isBetter(bestReady, switchingTo)) {
                    // put the old target back to ready/waiting and reset its "newarrival"
                    switchingTo.lastReadyTime = time;
                    readyQueue.add(switchingTo);
 
                    // choose the better one and restart CS
                    readyQueue.remove(bestReady);
                    switchingTo = bestReady;
                    csLeft = contextSwitchTime;
 
                    executionOrder.add(switchingTo.name);
                }
            }
 
            // (D) preemption check while running:
            //     preempt if a ready process is better than the running one (priority, then arrival)
            if (running != null) {
                Process bestReady = pickBest(readyQueue);
                if (bestReady != null && isBetter(bestReady, running)) {
                    // running leaves CPU -> goes back to ready; update its "newarrival"
                    running.lastReadyTime = time;
                    readyQueue.add(running);
                    running = null;
 
                    // start switching to bestReady
                    readyQueue.remove(bestReady);
                    switchingTo = bestReady;
                    csLeft = contextSwitchTime;
 
                    executionOrder.add(switchingTo.name);
                }
            }
 
            // (E) if CPU is idle and not switching, pick next
            if (running == null && csLeft == 0 && switchingTo == null) {
                Process next = pickBest(readyQueue);
                if (next != null) {
                    readyQueue.remove(next);
 
                    if (!startedOnce) {
                        running = next; // first run: no CS time
                        startedOnce = true;
                        executionOrder.add(running.name);
                    } else {
                        switchingTo = next;
                        csLeft = contextSwitchTime;
                        executionOrder.add(switchingTo.name);
                    }
                }
            }
 
            // (F) if CS finished, start running the target now (after checks at this second)
            if (running == null && csLeft == 0 && switchingTo != null) {
                running = switchingTo;
                switchingTo = null;
                startedOnce = true;
            }
 
            // (G) consume exactly 1 time unit: running OR CS OR idle
            if (running != null) {
                running.remaining--;
                time++;
 
                if (running.remaining == 0) {
                    running.finishTime = time;
                    completed++;
                    running = null;
                }
            } else if (csLeft > 0) {
                csLeft--;
                time++;
            } else {
                // idle
                time++;
            }
        }
 
        // WT & TAT
        int totalWT = 0, totalTAT = 0;
 
        // Output in arrival order (then name) for stable printing
        processes.sort(Comparator.<Process>comparingInt(p -> p.arrival).thenComparing(p -> p.name));
 
        for (Process p : processes) {
            p.turnaroundTime = p.finishTime - p.arrival;
            p.waitingTime = p.turnaroundTime - p.burst;
            totalWT += p.waitingTime;
            totalTAT += p.turnaroundTime;
        }
 
        System.out.println("\nexecutionOrder: " + executionOrder);
 
        System.out.println("\nprocessResults:");
        for (Process p : processes) {
            System.out.println("{name: " + p.name
                    + ", waitingTime: " + p.waitingTime
                    + ", turnaroundTime: " + p.turnaroundTime + "}");
        }
 
        System.out.printf("\naverageWaitingTime: %.2f\n", (double) totalWT / n);
        System.out.printf("averageTurnaroundTime: %.2f\n", (double) totalTAT / n);
    }
}