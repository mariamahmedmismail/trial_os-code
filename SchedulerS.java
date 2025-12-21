
import java.util.*;

class Process {
    String name;
    int arrival;
    int burst;
    int remaining;
    int finishTime;
    int waitingTime;
    int turnaroundTime;
    int priority;
    int waitingCounter; // for aging

    Process(String name, int arrival, int burst, int priority) {
        this.name = name;
        this.arrival = arrival;
        this.burst = burst;
        this.remaining = burst;
        this.priority = priority;
        this.waitingCounter = 0;
    }
}

public class SchedulerSys {

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        ArrayList<Process> processes = new ArrayList<>();
        ArrayList<Process> readyQueue = new ArrayList<>();
        ArrayList<String> executionOrder = new ArrayList<>();

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

        int time = 0;
        int completed = 0;
        Process lastProcess = null; // for context switching

        while (completed < n) {

            // Add arrived processes to ready queue
            for (Process p : processes) {
                if (p.arrival <= time && p.remaining > 0 && !readyQueue.contains(p)) {
                    readyQueue.add(p);
                }
            }

            if (readyQueue.isEmpty()) {
                executionOrder.add("Idle");
                time++;
                continue;
            }

            // Select highest priority process
            Process current = readyQueue.get(0);
            for (Process p : readyQueue) {
                if (p.priority < current.priority ||
                   (p.priority == current.priority && p.arrival < current.arrival)) {
                    current = p;
                }
            }

            // Context Switch check
            if (lastProcess != null && lastProcess != current) {
                for (int i = 0; i < contextSwitchTime; i++) {
                    executionOrder.add("CS");
                    time++;
                }
            }

            lastProcess = current;

            // Reset waiting counter for running process
            current.waitingCounter = 0;

            // Execute for 1 time unit
            executionOrder.add(current.name);
            current.remaining--;

            // Aging
            for (Process p : readyQueue) {
                if (p != current) {
                    p.waitingCounter++;
                    if (p.waitingCounter == agingInterval) {
                        p.priority = Math.max(0, p.priority - 1);
                        p.waitingCounter = 0;
                    }
                }
            }

            // If finished
            if (current.remaining == 0) {
                completed++;
                current.finishTime = time + 1;
                readyQueue.remove(current);
            }

            time++;
        }

        // Calculate WT & TAT
        int totalWT = 0, totalTAT = 0;

        for (Process p : processes) {
            p.turnaroundTime = p.finishTime - p.arrival;
            p.waitingTime = p.turnaroundTime - p.burst;
            totalWT += p.waitingTime;
            totalTAT += p.turnaroundTime;
        }

        System.out.println("\nExecution Order:");
        System.out.println(executionOrder);

        System.out.println("\nProcess Results:");
        for (Process p : processes) {
            System.out.println(
                "Process " + p.name +
                " | Waiting Time = " + p.waitingTime +
                " | Turnaround Time = " + p.turnaroundTime
            );
        }

        System.out.printf("\nAverage Waiting Time: %.2f\n", (double) totalWT / n);
        System.out.printf("Average Turnaround Time: %.2f\n", (double) totalTAT / n);
    }
}