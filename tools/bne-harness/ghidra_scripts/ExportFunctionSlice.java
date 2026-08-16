// Headless Ghidra post-script used by bne_static_analysis.py.
// Output is deliberately a tiny TSV contract so the Python adapter, not a
// Ghidra database version, owns the durable evidence schema.

import java.io.File;
import java.io.PrintWriter;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;

public class ExportFunctionSlice extends GhidraScript {
    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 3) {
            throw new IllegalArgumentException(
                "usage: ExportFunctionSlice.java ADDRESS SPAN OUTPUT");
        }
        Address start = toAddr(Long.decode(args[0]));
        long span = Long.parseLong(args[1]);
        Address end = start.add(span - 1);
        try (PrintWriter out = new PrintWriter(new File(args[2]), "UTF-8")) {
            out.println("# address\tbytes\tmnemonic\toperands\ttarget");
            InstructionIterator iterator = currentProgram.getListing()
                .getInstructions(new AddressSet(start, end), true);
            while (iterator.hasNext() && !monitor.isCancelled()) {
                Instruction instruction = iterator.next();
                byte[] bytes = instruction.getBytes();
                StringBuilder encoded = new StringBuilder();
                for (byte value : bytes) {
                    encoded.append(String.format("%02x", value & 0xff));
                }
                Address[] flows = instruction.getFlows();
                String target = flows.length == 0 ? "-" :
                    "0x" + flows[0].toString(false, false);
                String rendered = instruction.toString();
                String mnemonic = instruction.getMnemonicString();
                String operands = rendered.length() <= mnemonic.length() ? "" :
                    rendered.substring(mnemonic.length()).trim();
                out.printf("0x%s\t%s\t%s\t%s\t%s%n",
                    instruction.getAddress().toString(false, false), encoded,
                    mnemonic, operands.replace('\t', ' '), target);
            }
        }
    }
}
