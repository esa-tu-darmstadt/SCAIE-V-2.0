#include "tapasco.hpp"
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <iterator>
#include <memory>
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <vector>
#include <algorithm>

//Range of plausible PE IDs (see tapasco-riscv Makefile)
#define PE_ID_MIN 1748
#define PE_ID_MAX 1800

#define DRAM_ADDR 0
#define PROGRAM_SIZE_MAX (2*1024*1024)
#define RESULTS_SIZE 0x2000
#define RESULTS_OFFS (PROGRAM_SIZE_MAX-0x1000-0-RESULTS_SIZE)

using namespace tapasco;

void read_binary_file(std::string filename, std::vector<uint8_t> &buffer) {
    int fd = open(filename.c_str(), O_RDONLY);
    struct stat sb = {};
    fstat(fd, &sb);
    uint8_t *buf = (uint8_t*)mmap(NULL, sb.st_size, PROT_READ, MAP_SHARED, fd, 0);
    buffer.insert(buffer.end(), buf, buf + sb.st_size);
    munmap(buf, sb.st_size);
    close(fd);
    std::cout << "Finished reading binary file. Received " << buffer.size() << " bytes." << std::endl;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        printf("ERROR: Missing program binary as argument");
        exit(1);
    }
    uint32_t devID = 0;
    if (argc > 2) {
        devID = atoi(argv[2]);
    }
    const char *peName = nullptr;
    if (argc > 3) {
        peName = argv[3];
    }

    Tapasco tapasco(tlkm_access::TlkmAccessExclusive, devID);
    TapascoDevice &dev = tapasco.device();

    int peID = PE_ID_MIN;
    if (peName != nullptr) {
        auto peID_ = dev.get_pe_id(std::string(peName));
        peID = (peID_ == (PEId)-1) ? -1 : (int)peID_;
    }
    else {
        for (peID = PE_ID_MIN; peID < PE_ID_MAX; ++peID) {
            if (dev.num_pes(peID) > 0)
                break;
        }
    }
    if (peID == -1 || dev.num_pes(peID) == 0) {
        std::cout << "ERROR: Could not find a RISC-V PE." << std::endl;
        exit(1);
    }

    std::vector<uint8_t> program_buffer;
    read_binary_file(argv[1], program_buffer);

    if (program_buffer.size() == 0) {
        std::cout << "ERROR: Could not read program." << std::endl;
        exit(1);
    }
    if (program_buffer.size() > PROGRAM_SIZE_MAX) {
        std::cout << "ERROR: Program exceeds expected size." << std::endl;
        exit(1);
    }
    program_buffer.resize(PROGRAM_SIZE_MAX); //fill rest with 0

    //Copy program to device memory (fixed address as defined in PE's axi_offset module)
    TapascoMemory devMemory = dev.default_memory();
    devMemory.copy_to(program_buffer.data(), DRAM_ADDR, program_buffer.size());

    uint64_t result_val = -1;
    RetVal<uint64_t> retval(&result_val);

    auto job = tapasco.launch(
        peID,                 // Processing Element ID
        retval,               // return value
        0                     // arg0 (placeholder)
    );

    std::cout << "Waiting for RISC-V " << std::endl;
    job();
    std::cout << "RISC-V return value: " << (result_val) << std::endl;

    std::vector<uint32_t> results_buffer(RESULTS_SIZE/sizeof(uint32_t));
    devMemory.copy_from(DRAM_ADDR+RESULTS_OFFS, (uint8_t*)results_buffer.data(), RESULTS_SIZE);

    auto iter_nonzero = std::find_if(results_buffer.rbegin(), results_buffer.rend(), [](uint32_t &val) { return val != 0; });
    size_t sizeNonzero = (iter_nonzero == results_buffer.rend()) ? 0 : std::distance(results_buffer.begin(), iter_nonzero.base());

    std::cout << "Non-zero results size: " << std::hex << (sizeNonzero*sizeof(uint32_t)) << " bytes" << std::endl;
    for (size_t i = 0; i < sizeNonzero; ++i) {
        printf("%08x\n", results_buffer[i]);
    }

    return 0;
}
