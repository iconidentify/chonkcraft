"""A tiny non-proprietary x86-32 decision, assembled by hand for the tests.

Nothing shipped is used here. These are the bytes of a function written for
this test and nothing else, so the micro-oracle can be proved end to end
without a copy of the game anywhere near a repository.

The function it assembles is the shape the tool exists for: two integer inputs,
one input read out of memory, one conditional branch, and a boundary where
changing a single input by one flips the outcome.

    int decide(void)            ; ecx = counter, edx = limit
    {
        int reach = *(int *)0x00200000;     ; memory-backed input
        if (ecx + reach > edx) {            ; the decision
            *(int *)0x00200010 = 1;         ; the written outcome
            return 1;
        }
        *(int *)0x00200010 = 0;
        return 0;
    }
"""

from __future__ import annotations

CODE_BASE = 0x00100000
DATA_BASE = 0x00200000
STACK_BASE = 0x00300000
STACK_BYTES = 0x1000

#: Where the memory-backed input and the written outcome live.
REACH_ADDRESS = DATA_BASE
OUTCOME_ADDRESS = DATA_BASE + 0x10


def decision_code() -> bytes:
    """The bytes of `decide`, hand-assembled and commented instruction by
    instruction so the test does not depend on an assembler being installed.
    """
    return bytes([
        0x8B, 0x05, *DATA_BASE.to_bytes(4, "little"),   # mov eax, [0x200000]
        0x01, 0xC8,                                     # add eax, ecx
        0x39, 0xD0,                                     # cmp eax, edx
        0x7E, 0x10,                                     # jle +16 -> not_greater
        0xC7, 0x05, *OUTCOME_ADDRESS.to_bytes(4, "little"),
        0x01, 0x00, 0x00, 0x00,                         # mov [0x200010], 1
        0xB8, 0x01, 0x00, 0x00, 0x00,                   # mov eax, 1
        0xC3,                                           # ret
        # not_greater:
        0xC7, 0x05, *OUTCOME_ADDRESS.to_bytes(4, "little"),
        0x00, 0x00, 0x00, 0x00,                         # mov [0x200010], 0
        0xB8, 0x00, 0x00, 0x00, 0x00,                   # mov eax, 0
        0xC3,                                           # ret
    ])


def nested_code() -> bytes:
    """A second function with two conditional branches and a helper call.

        int decide2(void)          ; ecx = counter, edx = limit
        {
            if (ecx >= 8) return 2;             ; first branch, early out
            int reach = helper();               ; a real call, really executed
            if (ecx + reach > edx) return 1;    ; second branch
            return 0;
        }
        int helper(void) { return *(int *)0x00200000; }

    The helper is reached with a `call` and returns with `ret`, so a replay
    that mishandles the stack cannot pass.
    """
    helper_offset = 0x40
    body = bytearray([
        0x83, 0xF9, 0x08,                               # cmp ecx, 8
        0x7C, 0x06,                                     # jl +6 -> below
        0xB8, 0x02, 0x00, 0x00, 0x00,                   # mov eax, 2
        0xC3,                                           # ret
    ])
    # below:
    call_site = len(body)
    displacement = helper_offset - (call_site + 5)
    body += bytes([0xE8, *displacement.to_bytes(4, "little", signed=True)])
    body += bytes([
        0x01, 0xC8,                                     # add eax, ecx
        0x39, 0xD0,                                     # cmp eax, edx
        0x7E, 0x06,                                     # jle +6 -> zero
        0xB8, 0x01, 0x00, 0x00, 0x00,                   # mov eax, 1
        0xC3,                                           # ret
        0xB8, 0x00, 0x00, 0x00, 0x00,                   # mov eax, 0
        0xC3,                                           # ret
    ])
    body += bytes([0x90] * (helper_offset - len(body)))  # pad to the helper
    body += bytes([
        0x8B, 0x05, *DATA_BASE.to_bytes(4, "little"),   # mov eax, [0x200000]
        0xC3,                                           # ret
    ])
    return bytes(body)


def snapshot_document(*, counter: int = 3, limit: int = 9, reach: int = 4,
        code: bytes | None = None, expected: dict | None = None,
        inputs: bool = True) -> dict:
    """A complete snapshot of one invocation of the synthetic decision."""
    body = decision_code() if code is None else code
    data = bytearray(0x1000)
    data[0:4] = reach.to_bytes(4, "little")
    stack = bytearray(STACK_BYTES)
    # The return address the function pops: the sentinel the replay stops at.
    stack[0x800:0x804] = (0x00FF0000).to_bytes(4, "little")
    document = {
        "schema": 1,
        "entry": CODE_BASE,
        "return_sentinel": 0x00FF0000,
        "executable_sha256": None,
        "registers": {
            "eax": 0, "ebx": 0, "ecx": counter, "edx": limit,
            "esi": 0, "edi": 0, "ebp": STACK_BASE + 0x900,
            "esp": STACK_BASE + 0x800,
            "eip": CODE_BASE, "eflags": 0x202,
        },
        "segments": [
            {"address": CODE_BASE, "hex": body.hex(), "access": "rx",
             "label": "synthetic-decision"},
            {"address": DATA_BASE, "hex": bytes(data).hex(), "access": "rw",
             "label": "synthetic-data"},
            {"address": STACK_BASE, "hex": bytes(stack).hex(), "access": "rw",
             "label": "synthetic-stack"},
        ],
        "inputs": [
            {"name": "counter", "kind": "register", "register": "ecx"},
            {"name": "limit", "kind": "register", "register": "edx"},
            {"name": "reach", "kind": "memory", "address": REACH_ADDRESS,
             "width": 4},
        ] if inputs else [],
        "expected": expected or {},
        "provenance": {"kind": "synthetic", "source": "synthetic_decision.py"},
    }
    return document
