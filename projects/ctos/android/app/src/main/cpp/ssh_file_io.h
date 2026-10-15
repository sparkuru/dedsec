#ifndef CTOS_SSH_FILE_IO_H
#define CTOS_SSH_FILE_IO_H
#include "ssh_file_view.h"
#include <stdio.h>
#include <sys/stat.h>

#define CTOS_SSH_VIRTUAL_HOME "/ctos-vault"
#define CTOS_SSH_IO_STREAMS 128
#define CTOS_SSH_IO_OPENS 1024
#define CTOS_SSH_IO_READ_BYTES (64U * 1024U * 1024U)

struct ctos_ssh_reader {
    FILE *stream;
    unsigned char *mapping;
    size_t length;
    int fd, locked, live, failed;
};
struct ctos_ssh_io {
    const struct ctos_ssh_view *view;
    int (*close_stream)(FILE *);
    struct ctos_ssh_reader readers[CTOS_SSH_IO_STREAMS];
    unsigned int opens, held_bytes, read_bytes;
    int active, error;
};
int ctos_ssh_io_init(struct ctos_ssh_io *, const struct ctos_ssh_view *, int (*)(FILE *));
int ctos_ssh_io_resolve(const char *, int, char [513]);
FILE *ctos_ssh_io_open(struct ctos_ssh_io *, const char *, unsigned int);
int ctos_ssh_io_owns(const struct ctos_ssh_io *, FILE *);
int ctos_ssh_io_close(struct ctos_ssh_io *, FILE *);
int ctos_ssh_io_destroy(struct ctos_ssh_io *);
int ctos_ssh_io_stat(struct ctos_ssh_io *, const char *, struct stat *);
/* Unique immediate children of an owned virtual directory; no host opendir. */
int ctos_ssh_io_child(struct ctos_ssh_io *, const char *, unsigned int, char [256]);
#endif
