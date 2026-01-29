# NailGun - Custom ISAX Integration Flow

NailGun is intended as a wrapper for configuring and executing our ISAX integration flow (CoreDSL &rarr; Treenail &rarr; Longnail &rarr; SCAIE-V &rarr; extended RISC-V core &rarr; cocotb simulation &rarr; LibreLane).

Note that Longnail is not released publically. Using only public tools, Nailgun supports ISAXes given as .sv/.yaml.

The SCAIE-V repository contains setup instructions for the public portion (examples directory).

## Plugins

Nailgun provides very rudimentary plugin support.
LibreLane was integrated as synthesis plugin and can be used as an example to develop further plugins.
