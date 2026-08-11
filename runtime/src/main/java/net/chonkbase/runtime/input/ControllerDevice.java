package net.chonkbase.runtime.input;

/**
 * One controller handle owned by a {@link ControllerBackend}.
 *
 * <p>The native handle is intentionally package-private. Game code receives
 * stable identity and diagnostics without depending on SDL or JNA types.
 */
public final class ControllerDevice {
    private final int instanceId;
    private final String name;
    private final String type;
    private final String path;
    private final String guid;
    final Object nativeHandle;
    volatile boolean attached = true;

    public ControllerDevice(int instanceId, String name, String type, String path, String guid) {
        this(instanceId, name, type, path, guid, null);
    }

    ControllerDevice(
            int instanceId,
            String name,
            String type,
            String path,
            String guid,
            Object nativeHandle) {
        this.instanceId = instanceId;
        this.name = name == null || name.isBlank() ? "Unknown Controller" : name;
        this.type = type == null || type.isBlank() ? "Game Controller" : type;
        this.path = path == null || path.isBlank() ? null : path;
        this.guid = guid == null || guid.isBlank() ? null : guid;
        this.nativeHandle = nativeHandle;
    }

    public int instanceId() {
        return instanceId;
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public String path() {
        return path;
    }

    public String guid() {
        return guid;
    }

    public boolean attached() {
        return attached;
    }

    public String bindingId() {
        if (guid != null) {
            return "guid:" + guid;
        }
        if (path != null) {
            return "path:" + path;
        }
        return "instance:" + instanceId + ":" + name;
    }

    String deviceKey() {
        if (guid != null) {
            return "guid:" + guid;
        }
        return path == null ? null : "path:" + path;
    }

    public String describe() {
        StringBuilder description = new StringBuilder(name)
                .append(" (")
                .append(type)
                .append(", instance=")
                .append(instanceId)
                .append(')');
        if (path != null) {
            description.append(" path=").append(path);
        }
        if (guid != null) {
            description.append(" guid=").append(guid);
        }
        return description.toString();
    }
}
