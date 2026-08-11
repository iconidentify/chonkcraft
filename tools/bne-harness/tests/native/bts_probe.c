#include <stdint.h>

volatile uint16_t branch_witness_value = 9;

__attribute__((noinline)) static void update_value(int wait) {
    if (wait >= 14) {
        branch_witness_value = 8;
    } else {
        branch_witness_value = 10;
    }
}

int main(void) {
    update_value(14);
    return branch_witness_value == 8 ? 0 : 1;
}
