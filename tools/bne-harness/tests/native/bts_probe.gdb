set pagination off
set confirm off
break update_value
run
watch branch_witness_value
record btrace bts
continue
printf "BNEWITNESS watch=0x%lx before=9 after=%u\n", &branch_witness_value, branch_witness_value
record instruction-history
record stop
quit
