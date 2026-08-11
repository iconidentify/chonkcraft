import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import systems.crigges.jmpq3.BlockTable;
import systems.crigges.jmpq3.JMpqEditor;
import systems.crigges.jmpq3.MPQOpenOption;
import systems.crigges.jmpq3.MpqFile;

/** Read-only inventory/extraction helper for the self-extracting BNE MPQ. */
public final class MpqTool {

    private static final String BNE_202_SHA256 =
            "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807";

    private MpqTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4
                || !(args[0].equals("list") || args[0].equals("extract")
                        || args[0].equals("blocks") || args[0].equals("extract-name")
                        || args[0].equals("extract-patch"))) {
            System.err.println("usage: MpqTool list archive | "
                    + "extract archive destination | blocks archive destination | "
                    + "extract-name archive archived-name destination | "
                    + "extract-patch archive destination");
            System.exit(2);
        }
        Path archive = Path.of(args[1]).toAbsolutePath();
        try (JMpqEditor editor = new JMpqEditor(archive,
                MPQOpenOption.READ_ONLY, MPQOpenOption.FORCE_V0)) {
            List<String> names = editor.getFileNames().stream()
                    .sorted(Comparator.comparing(String::toLowerCase))
                    .toList();
            if (args[0].equals("list")) {
                System.out.println("files=" + names.size()
                        + " blocks=" + editor.getTotalFileCount());
                names.forEach(System.out::println);
                return;
            }
            if (args[0].equals("blocks")) {
                if (args.length != 3) {
                    throw new IllegalArgumentException("blocks requires a destination");
                }
                dumpBlocks(editor, Path.of(args[2]).toAbsolutePath().normalize());
                return;
            }
            if (args[0].equals("extract-name")) {
                if (args.length != 4) {
                    throw new IllegalArgumentException(
                            "extract-name requires an archived name and destination");
                }
                Path destination = Path.of(args[3]).toAbsolutePath().normalize();
                Files.createDirectories(destination.getParent());
                editor.extractFile(args[2], destination.toFile());
                System.out.println("extracted=" + args[2] + " destination=" + destination);
                return;
            }
            if (args[0].equals("extract-patch")) {
                if (args.length != 3) {
                    throw new IllegalArgumentException(
                            "extract-patch requires a destination");
                }
                extractPatch(editor, archive,
                        Path.of(args[2]).toAbsolutePath().normalize());
                return;
            }
            if (args.length != 3) {
                throw new IllegalArgumentException("extract requires a destination");
            }
            Path destination = Path.of(args[2]).toAbsolutePath().normalize();
            Files.createDirectories(destination);
            for (String name : names) {
                Path output = safeDestination(destination, name);
                Files.createDirectories(output.getParent());
                editor.extractFile(name, output.toFile());
            }
            System.out.println("extracted=" + names.size() + " destination=" + destination);
        }
    }

    private static void extractPatch(JMpqEditor editor, Path archive, Path destination)
            throws Exception {
        Files.createDirectories(destination);
        Path listPath = destination.resolve(".patch.lst");
        editor.extractFile("patch.lst", listPath.toFile());
        List<String> extracted = new ArrayList<>();
        for (String line : Files.readAllLines(listPath, StandardCharsets.ISO_8859_1)) {
            line = line.replace("\0", "").strip();
            if (line.isEmpty() || line.equals("*")) {
                continue;
            }
            String[] fields = line.split(";", -1);
            if (fields.length < 2 || fields[0].isBlank() || fields[1].isBlank()) {
                throw new IOException("invalid patch.lst line: " + line);
            }
            Path output = safeDestination(destination, fields[0]);
            Files.createDirectories(output.getParent());
            Path payload = output.resolveSibling("." + output.getFileName() + ".payload");
            editor.extractFile(fields[1], payload.toFile());
            byte[] wrapped = Files.readAllBytes(payload);
            Files.delete(payload);
            if (wrapped.length < 24) {
                throw new IOException("short Blizzard patch payload: " + fields[1]);
            }
            ByteBuffer header = ByteBuffer.wrap(wrapped).order(ByteOrder.LITTLE_ENDIAN);
            if (header.getInt(0) != 0x01040018 || header.getInt(12) != wrapped.length - 24) {
                throw new IOException("unexpected Blizzard patch header: " + fields[1]);
            }
            Files.write(output, Arrays.copyOfRange(wrapped, 24, wrapped.length));
            extracted.add(destination.relativize(output).toString().replace('\\', '/'));
        }
        Files.delete(listPath);
        extracted.sort(String.CASE_INSENSITIVE_ORDER);
        Path target = destination.resolve("Warcraft II BNE.exe");
        if (!Files.isRegularFile(target) || !hash(target).equals(BNE_202_SHA256)) {
            throw new IOException("official patch did not produce the pinned BNE 2.02b target");
        }

        StringBuilder manifest = new StringBuilder();
        manifest.append("{\n")
                .append("  \"schema\": 1,\n")
                .append("  \"identity\": \"warcraft-ii-bne-2.02b-official-patch-target\",\n")
                .append("  \"source_patch\": {\n")
                .append("    \"name\": \"").append(archive.getFileName()).append("\",\n")
                .append("    \"bytes\": ").append(Files.size(archive)).append(",\n")
                .append("    \"sha256\": \"").append(hash(archive)).append("\"\n")
                .append("  },\n")
                .append("  \"files\": [\n");
        for (int index = 0; index < extracted.size(); index++) {
            String name = extracted.get(index);
            Path file = destination.resolve(name);
            manifest.append("    {\"path\": \"").append(name).append("\", \"bytes\": ")
                    .append(Files.size(file)).append(", \"sha256\": \"")
                    .append(hash(file)).append("\"}");
            manifest.append(index + 1 == extracted.size() ? "\n" : ",\n");
        }
        manifest.append("  ]\n}\n");
        Files.writeString(destination.resolve("target-manifest.json"), manifest,
                StandardCharsets.UTF_8);
        System.out.println("extracted=" + extracted.size() + " destination=" + destination);
    }

    private static String hash(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void dumpBlocks(JMpqEditor editor, Path destination) throws Exception {
        Files.createDirectories(destination);
        List<BlockTable.Block> blocks = editor.getBlockTable().getAllVaildBlocks();
        int extracted = 0;
        int skipped = 0;
        for (int index = 0; index < blocks.size(); index++) {
            BlockTable.Block block = blocks.get(index);
            String stem = String.format("block-%04d-%d-%08x", index,
                    block.getNormalSize(), block.getFlags());
            try {
                MpqFile file = editor.getMpqFileByBlock(block);
                file.extractToFile(destination.resolve(stem + ".bin").toFile());
                extracted++;
            } catch (IOException | RuntimeException failure) {
                Files.writeString(destination.resolve(stem + ".error.txt"),
                        failure.toString() + System.lineSeparator());
                skipped++;
            }
        }
        System.out.println("blocks=" + blocks.size() + " extracted=" + extracted
                + " skipped=" + skipped + " destination=" + destination);
    }

    static Path safeDestination(Path root, String archivedName) throws IOException {
        String portable = archivedName.replace('\\', '/');
        Path relative = Path.of(portable).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("unsafe MPQ path " + archivedName);
        }
        Path destination = root.resolve(relative).normalize();
        if (!destination.startsWith(root)) {
            throw new IOException("unsafe MPQ path " + archivedName);
        }
        return destination;
    }
}
