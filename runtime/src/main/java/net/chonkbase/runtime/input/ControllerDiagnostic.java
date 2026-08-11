package net.chonkbase.runtime.input;

/**
 * Live, fail-soft enumeration probe for developer and packaged builds.
 *
 * <p>Exit remains successful when SDL is absent so the output can diagnose a
 * keyboard-only machine without breaking broader smoke scripts.
 */
public final class ControllerDiagnostic {
    private ControllerDiagnostic() {}

    public static void main(String[] args) {
        LastInputFamilyTracker families = new LastInputFamilyTracker();
        try (ControllerSession session =
                ControllerSession.startDefault(action -> {}, families, () -> {})) {
            System.out.println("status=" + session.status());
            System.out.println("diagnostic=" + session.diagnostic());
            ListPrinter.print(session.connectedControllers());
        }
    }

    private static final class ListPrinter {
        private ListPrinter() {}

        static void print(
                java.util.List<ControllerInputHub.ConnectedController> controllers) {
            System.out.println("boundControllers=" + controllers.size());
            for (ControllerInputHub.ConnectedController controller : controllers) {
                System.out.println(
                        "slot="
                                + (controller.slot() + 1)
                                + " name="
                                + controller.name()
                                + " binding="
                                + controller.bindingId());
            }
        }
    }
}
