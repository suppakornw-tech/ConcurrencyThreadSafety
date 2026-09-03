import java.util.concurrent.CountDownLatch;

/**
 * เครื่องมือทดลองที่ 2 — ไฟล์นี้ให้มาแล้ว ไม่ต้องแก้
 *
 * RaceDemo ทดสอบแค่ deposit ซึ่งเป็น read-modify-write ธรรมดา
 * ไฟล์นี้ทดสอบ withdraw ซึ่งอันตรายกว่า เพราะเป็น check-then-act
 *
 * ความต่างที่สำคัญ
 * ---------------
 * deposit ผิด  -> ตัวเลขเพี้ยน แต่ยังอยู่ในช่วงที่เป็นไปได้
 * withdraw ผิด -> ยอดติดลบ ทั้งที่ในโค้ดมี if (balance >= amount) ป้องกันอยู่ชัด ๆ
 *                 คือกฎทางธุรกิจถูกละเมิด ซึ่งร้ายแรงกว่ามากในระบบจริง
 *
 * การทดลอง: มีเงิน 20,000 บาท แต่ปล่อย 8 เธรดพยายามถอนทีละบาท รวม 40,000 ครั้ง
 * ถ้าโค้ดถูกต้อง ต้องถอนสำเร็จพอดี 20,000 ครั้ง แล้วยอดเหลือ 0 พอดี
 * ครั้งที่ 20,001 เป็นต้นไปต้องถูกปฏิเสธทุกครั้ง
 *
 * วิธีรัน:  java OverdraftDemo
 */
public class OverdraftDemo {

    private static final int ROUNDS = 20;
    private static final int RACERS = 4;
    private static final int INITIAL = 100000;
    private static final int ATTEMPTS_EACH = 50000;  // รวม 200,000 ครั้ง มากกว่าเงินที่มีเท่าตัว

    public static void main(String[] args) throws Exception {
        System.out.println("=== Demo 2: Overdraft (withdraw) ===");
        System.out.println("Starting balance : " + INITIAL);
        System.out.println("Withdraw attempts: " + (RACERS * ATTEMPTS_EACH)
                + "  (" + RACERS + " threads x " + ATTEMPTS_EACH + ", 1 unit each)");
        System.out.println("Correct result   : exactly " + INITIAL
                + " succeed, final balance 0\n");

        System.out.println(" round  |  succeeded  |  should be  |  final balance");
        System.out.println("--------+-------------+-------------+----------------");

        int badRounds = 0;
        int worstBalance = 0;
        int mostSuccesses = 0;

        for (int round = 1; round <= ROUNDS; round++) {
            int[] result = runOneRound();
            int succeeded = result[0];
            int left = result[1];

            boolean bad = (succeeded != INITIAL) || (left != 0);
            if (bad) {
                badRounds++;
            }
            if (left < worstBalance) {
                worstBalance = left;
            }
            if (succeeded > mostSuccesses) {
                mostSuccesses = succeeded;
            }

            System.out.printf("  %2d    |  %,9d  |  %,9d  |  %,10d%s%n",
                    round, succeeded, INITIAL, left, (bad ? "   <-- WRONG" : ""));
        }

        System.out.println();
        System.out.println("=== Experiment summary ===");
        System.out.println("Rounds with a wrong result : " + badRounds + " / " + ROUNDS);
        System.out.println("Most withdrawals approved  : " + mostSuccesses
                + "   (only " + INITIAL + " were funded)");
        System.out.println("Lowest balance ever seen   : " + worstBalance);
        System.out.println();

        if (badRounds == 0) {
            System.out.println("Every round approved exactly " + INITIAL + " withdrawals");
            System.out.println("and finished at zero. withdraw() is holding its promise.");
            System.out.println();
            System.out.println("If you have not fixed Account.withdraw yet, this machine");
            System.out.println("simply never interleaved the threads badly enough.");
            System.out.println("Run in interpreted mode for a harsher test:");
            System.out.println("    java -Xint OverdraftDemo");
        } else {
            System.out.println("The bank approved more withdrawals than it had money for,");
            System.out.println("and the balance went negative.");
            System.out.println();
            System.out.println("Look at Account.withdraw again. The guard");
            System.out.println("    if (balance >= amount)");
            System.out.println("is still there and still correct. It was simply asked");
            System.out.println("a question whose answer expired before it was acted on.");
            System.out.println();
            System.out.println("This is check-then-act. Fix TODO 1.2 in Account.java");
            System.out.println("and run this file again.");
        }
    }

    /** @return {จำนวนครั้งที่ถอนสำเร็จ, ยอดคงเหลือสุดท้าย} */
    private static int[] runOneRound() throws InterruptedException {
        final Account acc = new Account(1, INITIAL);

        // นับแยกช่องใครช่องมัน แล้วค่อยรวมตอนท้าย
        // ถ้าใช้ตัวนับร่วมกัน การนับเองจะกลายเป็นจุดซิงก์ที่บังคับให้เธรด
        // เข้าคิวกัน จนไม่เหลือจังหวะให้ race ใน withdraw ได้โผล่
        final int[] hits = new int[RACERS];

        // ประตูปล่อยตัว — ให้ทุกเธรดพร้อมก่อน แล้วปล่อยพร้อมกัน
        // ถ้าปล่อยไล่ทีละตัว เธรดแรกอาจถอนจนเงินหมดก่อนเธรดสุดท้ายได้เริ่มด้วยซ้ำ
        final CountDownLatch ready = new CountDownLatch(RACERS);
        final CountDownLatch go = new CountDownLatch(1);

        Thread[] racers = new Thread[RACERS];
        for (int i = 0; i < RACERS; i++) {
            final int slot = i;
            racers[i] = new Thread(new Runnable() {
                public void run() {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    int mine = 0;
                    for (int k = 0; k < ATTEMPTS_EACH; k++) {
                        if (acc.withdraw(1)) {
                            mine++;
                        }
                    }
                    hits[slot] = mine;
                }
            });
        }

        for (int i = 0; i < RACERS; i++) {
        racers[i].start();
        }
        ready.await();
        go.countDown();
        for (int i = 0; i < RACERS; i++) {
            racers[i].join();
        }

        int total = 0;
        for (int i = 0; i < RACERS; i++) {
            total += hits[i];
        }
        return new int[]{total, acc.balance()};
    }
}
