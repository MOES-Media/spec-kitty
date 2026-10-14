package ai.speckitty.producer.launcher;

/** The entry of the native producer: no arguments, one request on standard input, one reply on standard output. See {@link Launcher}. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        int exit = new Launcher(() -> Dispatcher.discover(new ProduceHandler(Wiring.production()::produce), Main.class.getClassLoader()))
                .run(System.in, System.out);
        System.out.flush();
        if (exit != Launcher.EXIT_OK) {
            System.exit(exit);
        }
    }
}
