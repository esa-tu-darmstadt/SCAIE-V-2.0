ENTRY(_start)
MEMORY
{
    imem (rxa) : ORIGIN = 0x80000000, LENGTH = 1M
    dmem (rwa) : ORIGIN = 0x80100000, LENGTH = 1M
    ctrl (w) : ORIGIN = 0x11000000, LENGTH = 8K
}
SECTIONS
{
    __stack_size = 0x1000;
    __indata_size = 0;
    __ctx_size = 0;
    __resdata_size = 0x2000;

    . = ORIGIN(imem);

    .text.init : {
            * (.text.init);
    } > imem
    .text.irq : ALIGN(0x100) {
          * (.text.freertos_risc_v_trap_handler);
          * (.text.trap_handler)
    } > imem
    .text : {
          * (.text);
    } > imem
    .interp : { *(.interp) } > imem
    .eh_frame : {
        *(.eh_frame)
    } > imem

    . = ORIGIN(dmem);
    PROVIDE( _test_indata = ORIGIN(dmem) );
    . = ORIGIN(dmem) + __indata_size;
    .rodata : {
          * (.srodata*);
          * (.rodata*);
    } > dmem
    .sdata : {
          * (.sdata*);
    } > dmem
    .sbss : {
         * (.sbss*);
    } > dmem
    .data : {
        * (.data*);
    } > dmem
    .bss : {
         * (.bss*);
    } > dmem
    PROVIDE ( _heap_start = .);

    PROVIDE( _test_resdata = ORIGIN(dmem) + LENGTH(dmem) - __stack_size - __ctx_size - __resdata_size );

    .ctx_states ORIGIN(dmem) + LENGTH(dmem) - __stack_size - __ctx_size:
    {
        PROVIDE (_ctx_state_start = .);
        PROVIDE (_ctx_state_end = . + __ctx_size);
    } > dmem

    .stack ORIGIN(dmem) + LENGTH(dmem) - __stack_size:
    {
        PROVIDE( _sp = .  + __stack_size);
        PROVIDE (_stack_top = . + __stack_size);
    } > dmem

    PROVIDE( _ctrlmem = ORIGIN(ctrl) );
}
