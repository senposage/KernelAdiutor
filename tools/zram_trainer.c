#define _FILE_OFFSET_BITS 64
#define _GNU_SOURCE
#define ZDICT_STATIC_LINKING_ONLY

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "zdict.h"

#define PAGE_BYTES 4096
#define MIN_PAGES 128
#define MAX_PAGES 2048
#define MAX_BATCH 128
#define DICTIONARY_BYTES 16384

struct range {
    uint64_t first_page;
    uint64_t end_page;
};

static int eligible_mapping(const char *label)
{
    return !*label || !strcmp(label, "[heap]") ||
           !strncmp(label, "[anon:dalvik-", 13) ||
           !strncmp(label, "[anon:libc_malloc", 17) ||
           !strncmp(label, "[anon:scudo", 11) ||
           !strncmp(label, "[anon:partition_alloc", 21);
}

static int has_memory_headroom(void)
{
    FILE *input = fopen("/proc/meminfo", "r");
    char line[256];
    unsigned long available = 0;

    if (!input)
        return 0;
    while (fgets(line, sizeof(line), input)) {
        if (sscanf(line, "MemAvailable: %lu kB", &available) == 1)
            break;
    }
    fclose(input);
    return available >= 256 * 1024;
}

static int cpu_times(uint64_t *total, uint64_t *idle)
{
    FILE *input = fopen("/proc/stat", "r");
    if (!input)
        return 0;
    uint64_t user, nice, system, wait, irq, softirq, steal;
    int fields = fscanf(input, "cpu %" SCNu64 " %" SCNu64 " %" SCNu64 " %" SCNu64
                        " %" SCNu64 " %" SCNu64 " %" SCNu64 " %" SCNu64,
                        &user, &nice, &system, idle, &wait, &irq, &softirq, &steal);
    fclose(input);
    if (fields != 8)
        return 0;
    *total = user + nice + system + *idle + wait + irq + softirq + steal;
    return 1;
}

static int can_train(void)
{
    uint64_t start_total, start_idle, end_total, end_idle;
    if (!has_memory_headroom() || !cpu_times(&start_total, &start_idle))
        return 0;
    sleep(2);
    return has_memory_headroom() && cpu_times(&end_total, &end_idle) &&
           end_total > start_total && end_idle >= start_idle &&
           (end_idle - start_idle) * 4 >= (end_total - start_total) * 3;
}

static int matches_process(int process, const char *package)
{
    char name[256];
    int file = openat(process, "cmdline", O_RDONLY | O_CLOEXEC);
    if (file < 0)
        return 0;
    ssize_t size = read(file, name, sizeof(name));
    close(file);
    return size > (ssize_t)strlen(package) &&
           !memcmp(name, package, strlen(package) + 1);
}

static size_t read_mappings(int process, struct range *ranges, size_t capacity)
{
    int file = openat(process, "maps", O_RDONLY | O_CLOEXEC);
    if (file < 0)
        return 0;
    FILE *maps = fdopen(file, "r");
    if (!maps) {
        close(file);
        return 0;
    }
    size_t count = 0;
    char *line = NULL;
    size_t line_size = 0;
    while (count < capacity && getline(&line, &line_size, maps) > 0) {
        uint64_t start, end, inode;
        char perms[5];
        int label_offset = 0;
        line[strcspn(line, "\n")] = '\0';
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %4s %*s %*s %" SCNu64 " %n",
                   &start, &end, perms, &inode, &label_offset) != 4 ||
            !label_offset || strcmp(perms, "rw-p") || inode || end <= start ||
            !eligible_mapping(line + label_offset))
            continue;
        ranges[count++] = (struct range){start / PAGE_BYTES, end / PAGE_BYTES};
    }
    free(line);
    fclose(maps);
    return count;
}

static int capture(unsigned long pid, const char *package, unsigned long limit,
                   unsigned int seed)
{
    if (!pid || !limit || limit > MAX_BATCH || sysconf(_SC_PAGESIZE) != PAGE_BYTES)
        return 1;
    if (!has_memory_headroom())
        return 2;

    char path[64];
    snprintf(path, sizeof(path), "/proc/%lu", pid);
    int process = open(path, O_DIRECTORY | O_RDONLY | O_CLOEXEC);
    if (process < 0)
        return errno == ENOENT || errno == ESRCH ? 3 : 1;
    if (!matches_process(process, package))
        return 3;

    struct range ranges[4096];
    size_t count = read_mappings(process, ranges, sizeof(ranges) / sizeof(ranges[0]));
    int presence = openat(process, "pagemap", O_RDONLY | O_CLOEXEC);
    int memory = openat(process, "mem", O_RDONLY | O_CLOEXEC);
    if (!matches_process(process, package))
        return 3;
    if (!count || presence < 0 || memory < 0)
        return 1;

    unsigned char buffer[PAGE_BYTES];
    unsigned long emitted = 0;
    for (unsigned long attempt = 0; attempt < limit * 16 && emitted < limit; ++attempt) {
        const struct range *range = &ranges[rand_r(&seed) % count];
        uint64_t page = range->first_page +
                        rand_r(&seed) % (range->end_page - range->first_page);
        uint64_t entry;
        /* Do not deliberately fault in swapped or unallocated pages. */
        if (pread(presence, &entry, sizeof(entry), page * sizeof(entry)) != sizeof(entry) ||
            !(entry & (UINT64_C(1) << 63)) ||
            pread(memory, buffer, sizeof(buffer), page * PAGE_BYTES) != sizeof(buffer))
            continue;
        if (fwrite(buffer, sizeof(buffer), 1, stdout) != 1)
            return 1;
        ++emitted;
    }
    return fflush(stdout) == 0 ? 0 : 1;
}

static size_t read_samples(unsigned char *samples, size_t expected_pages)
{
    if (expected_pages == 0)
        return fread(samples, 1, (MAX_PAGES + 1) * PAGE_BYTES, stdin);

    for (size_t i = 0; i < expected_pages; ++i) {
        uint32_t source;
        if (fread(&source, sizeof(source), 1, stdin) != 1 ||
            fread(samples + i * PAGE_BYTES, PAGE_BYTES, 1, stdin) != 1)
            return 0;
    }
    return fgetc(stdin) == EOF ? expected_pages * PAGE_BYTES : 0;
}

static int train(size_t expected_pages)
{
    if (!can_train())
        return 2;
    unsigned char *samples = malloc((MAX_PAGES + 1) * PAGE_BYTES);
    if (!samples)
        return 1;
    size_t bytes = read_samples(samples, expected_pages);
    size_t pages = bytes / PAGE_BYTES;
    if (ferror(stdin) || bytes % PAGE_BYTES || pages < MIN_PAGES || pages > MAX_PAGES) {
        free(samples);
        return 1;
    }
    size_t sizes[MAX_PAGES];
    for (size_t i = 0; i < pages; ++i)
        sizes[i] = PAGE_BYTES;
    /* fastCover includes a Zstandard header, which LZ4 does not use. */
    unsigned char dictionary[DICTIONARY_BYTES + 2048];
    ZDICT_fastCover_params_t options = {
        .d = 8, .f = 17, .steps = 4, .nbThreads = 1, .accel = 1,
        .splitPoint = 1.0,
    };
    size_t length = ZDICT_optimizeTrainFromBuffer_fastCover(
            dictionary, sizeof(dictionary), samples, sizes, pages, &options);
    free(samples);
    if (ZDICT_isError(length)) {
        fprintf(stderr, "%s\n", ZDICT_getErrorName(length));
        return 1;
    }
    size_t header = ZDICT_getDictHeaderSize(dictionary, length);
    if (ZDICT_isError(header) || header >= length)
        return 1;
    if (length - header < DICTIONARY_BYTES)
        return 4;
    /* Keep the tail of the trained content that LZ4 would prioritize. */
    return fwrite(dictionary + length - DICTIONARY_BYTES, DICTIONARY_BYTES, 1, stdout) == 1 &&
           fflush(stdout) == 0 ? 0 : 1;
}

int main(int argc, char **argv)
{
    if (argc == 2 && !strcmp(argv[1], "train"))
        return train(0);
    if (argc == 3 && !strcmp(argv[1], "train-checkpoint")) {
        unsigned long pages = strtoul(argv[2], NULL, 10);
        return pages >= MIN_PAGES && pages <= MAX_PAGES ? train(pages) : 1;
    }
    if (argc == 6 && !strcmp(argv[1], "capture"))
        return capture(strtoul(argv[2], NULL, 10), argv[3],
                       strtoul(argv[4], NULL, 10), strtoul(argv[5], NULL, 10));
    return 1;
}
