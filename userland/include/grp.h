/* <grp.h> for CC: Bare Metal: the group database (/etc/group), implemented in libbaremetal. */
#ifndef _GRP_H
#define _GRP_H

#include <stddef.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct group {
    char *gr_name;
    char *gr_passwd;
    gid_t gr_gid;
    char **gr_mem;
};

struct group *getgrnam(const char *name);
struct group *getgrgid(gid_t gid);
void setgrent(void);
struct group *getgrent(void);
void endgrent(void);
/* Every group of `user` (its primary `group` first); *ngroups is the capacity in, the count out. -1 if too small. */
int getgrouplist(const char *user, gid_t group, gid_t *groups, int *ngroups);
int initgroups(const char *user, gid_t group);
int setgroups(size_t size, const gid_t *list);

#ifdef __cplusplus
}
#endif

#endif
