/* <pwd.h> for CC: Bare Metal: the user database (/etc/passwd), implemented in libbaremetal. */
#ifndef _PWD_H
#define _PWD_H

#include <stddef.h>
#include <stdio.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct passwd {
    char *pw_name;
    char *pw_passwd;
    uid_t pw_uid;
    gid_t pw_gid;
    char *pw_gecos;
    char *pw_dir;
    char *pw_shell;
};

struct passwd *getpwnam(const char *name);
struct passwd *getpwuid(uid_t uid);
int getpwnam_r(const char *name, struct passwd *pw, char *buf, size_t size, struct passwd **result);
int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t size, struct passwd **result);
void setpwent(void);
struct passwd *getpwent(void);
void endpwent(void);
int putpwent(const struct passwd *pw, FILE *f);

#ifdef __cplusplus
}
#endif

#endif
