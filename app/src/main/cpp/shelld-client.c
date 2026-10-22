/*
 * shelld-client.c — ShellBox JNI client for the xnix-shelld privileged daemon.
 * Shizuku-free replacement: connects to abstract AF_UNIX @xnix-shelld and asks
 * the daemon (running at uid shell) to EXEC a command (streamed) or OPENPTY
 * (returns the PTY master fd via SCM_RIGHTS).
 *
 * JNI surface (com.shellbox.shell.ShelldClient):
 *   static native int  openPty(String cmd)   -> PTY master fd (>=0) or -errno
 *   static native int  connectExec(String cmd) -> a connected socket fd whose
 *       stream is the command's combined stdout/stderr (caller reads it); the
 *       daemon closes after a 0-length frame + exit-code trailer.
 */
#include <jni.h>
#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#define SOCK_NAME "xnix-shelld"
#define OP_EXEC    1
#define OP_OPENPTY 2

static int shelld_connect(void) {
    int c = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (c < 0) return -errno;
    struct sockaddr_un sa = { .sun_family = AF_UNIX };
    sa.sun_path[0] = '\0';
    memcpy(sa.sun_path + 1, SOCK_NAME, strlen(SOCK_NAME));
    socklen_t slen = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(SOCK_NAME);
    if (connect(c, (struct sockaddr *)&sa, slen) < 0) { int e = -errno; close(c); return e; }
    return c;
}

static int send_req(int c, uint8_t op, const char *cmd) {
    uint32_t len = cmd ? (uint32_t)strlen(cmd) : 0;
    if (write(c, &op, 1) != 1) return -1;
    if (write(c, &len, sizeof len) != (ssize_t)sizeof len) return -1;
    if (len && write(c, cmd, len) != (ssize_t)len) return -1;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_shellbox_shell_ShelldClient_connectExec(JNIEnv *env, jclass cls, jstring jcmd) {
    (void)cls;
    const char *cmd = jcmd ? (*env)->GetStringUTFChars(env, jcmd, NULL) : NULL;
    int c = shelld_connect();
    if (c >= 0 && send_req(c, OP_EXEC, cmd) < 0) { close(c); c = -EIO; }
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    return c;   /* caller reads the command output from this fd, then closes */
}

JNIEXPORT jint JNICALL
Java_com_shellbox_shell_ShelldClient_openPty(JNIEnv *env, jclass cls, jstring jcmd) {
    (void)cls;
    const char *cmd = jcmd ? (*env)->GetStringUTFChars(env, jcmd, NULL) : NULL;
    int c = shelld_connect();
    int result = c;
    if (c >= 0) {
        if (send_req(c, OP_OPENPTY, cmd) == 0) {
            /* receive the PTY master fd via SCM_RIGHTS */
            char b; struct iovec iov = { .iov_base = &b, .iov_len = 1 };
            char cbuf[CMSG_SPACE(sizeof(int))] = {0};
            struct msghdr msg = { .msg_iov = &iov, .msg_iovlen = 1,
                                  .msg_control = cbuf, .msg_controllen = sizeof cbuf };
            result = -EIO;
            if (recvmsg(c, &msg, 0) >= 0) {
                for (struct cmsghdr *cm = CMSG_FIRSTHDR(&msg); cm; cm = CMSG_NXTHDR(&msg, cm)) {
                    if (cm->cmsg_level == SOL_SOCKET && cm->cmsg_type == SCM_RIGHTS) {
                        int fd; memcpy(&fd, CMSG_DATA(cm), sizeof fd);
                        result = fd;
                        break;
                    }
                }
            }
        } else result = -EIO;
        close(c);   /* the PTY master fd is independent of the control socket */
    }
    if (cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    return result;
}
