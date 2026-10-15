#ifndef CTOS_SSH_CONTROL_H
#define CTOS_SSH_CONTROL_H
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#define CTOS_SSH_CONTROL_HEADER 88U
#define CTOS_SSH_CONTROL_PAYLOAD 32768U
#define CTOS_SSH_CONTROL_LOAN 37U
enum ctos_ssh_control_kind {
    CTOS_CONTROL_JOIN = 1, CTOS_CONTROL_HOST_QUERY, CTOS_CONTROL_HOST_DECISION,
    CTOS_CONTROL_KEX_DONE, CTOS_CONTROL_SECRET_REQUEST, CTOS_CONTROL_FILE_LOAN,
    CTOS_CONTROL_LOAN_ACK, CTOS_CONTROL_AUTH_DONE, CTOS_CONTROL_CANCEL, CTOS_CONTROL_FAILURE
};
struct ctos_ssh_control_peer {
    int fd, failed;
    pid_t pid;
    uid_t uid;
    uint64_t sent, received;
    unsigned char role[32], connection[32];
};
struct ctos_ssh_control_frame {
    unsigned int kind, length;
    uint64_t sequence;
    int fd;
    unsigned char payload[CTOS_SSH_CONTROL_PAYLOAD];
};
/* No filesystem name/listener. Enable per-packet credentials before fork. */
int ctos_ssh_control_pair(int [2]);
int ctos_ssh_control_nonce(unsigned char [32]);
/* Ownership of fd transfers only on success. PID must come from owned launch. */
int ctos_ssh_control_init(struct ctos_ssh_control_peer *, int, pid_t,
    const unsigned char [32], const unsigned char [32]);
int ctos_ssh_control_close(struct ctos_ssh_control_peer *);
int ctos_ssh_control_frame_close(struct ctos_ssh_control_frame *);
/* BOOTTIME millisecond deadlines. Sender retains any supplied loan descriptor. */
int ctos_ssh_control_send(struct ctos_ssh_control_peer *, unsigned int,
    const unsigned char *, size_t, int, uint64_t);
/* Caller supplies a fresh frame with fd=-1, or closes its previous frame first. */
int ctos_ssh_control_receive(struct ctos_ssh_control_peer *, struct ctos_ssh_control_frame *, uint64_t);
#endif
