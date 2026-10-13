/* OSS (Open Sound System) for CC: Bare Metal: /dev/dspN are speakers. Each open is a stream; the kernel mixes the
   streams of a speaker. Formats: AFMT_S8 (the speaker's own), AFMT_U8, AFMT_S16_LE; 1 or 2 channels (mixed down);
   4000-96000 Hz (resampled to 48 kHz). Default: AFMT_S8, mono, 48000. GETODELAY tells how many bytes are written but
   not heard yet (for audio/video sync), RESET drops them, SYNC waits until they are handed to the speaker. */
#ifndef BAREMETAL_SOUNDCARD_H
#define BAREMETAL_SOUNDCARD_H
#include <sys/ioctl.h>
typedef struct audio_buf_info {
    int fragments;  /* free fragments */
    int fragstotal;
    int fragsize;
    int bytes;      /* free bytes */
} audio_buf_info;
#define SNDCTL_DSP_RESET 0x5000
#define SNDCTL_DSP_SYNC 0x5001
#define SNDCTL_DSP_SPEED ((int)0xc0045002)
#define SNDCTL_DSP_STEREO ((int)0xc0045003)
#define SNDCTL_DSP_GETBLKSIZE ((int)0xc0045004)
#define SNDCTL_DSP_SETFMT ((int)0xc0045005)
#define SNDCTL_DSP_CHANNELS ((int)0xc0045006)
#define SNDCTL_DSP_POST 0x5008
#define SNDCTL_DSP_SETFRAGMENT ((int)0xc004500a)
#define SNDCTL_DSP_GETFMTS ((int)0x8004500b)
#define SNDCTL_DSP_GETOSPACE ((int)0x8010500c)
#define SNDCTL_DSP_GETISPACE ((int)0x8010500d) /* recording: not supported (ENOTTY) */
#define SNDCTL_DSP_NONBLOCK 0x500e
#define SNDCTL_DSP_GETCAPS ((int)0x8004500f)
#define SNDCTL_DSP_GETODELAY ((int)0x80045017)
#define SOUND_PCM_WRITE_RATE SNDCTL_DSP_SPEED
#define SOUND_PCM_WRITE_CHANNELS SNDCTL_DSP_CHANNELS
#define AFMT_QUERY 0x00000000
#define AFMT_MU_LAW 0x00000001
#define AFMT_A_LAW 0x00000002
#define AFMT_U8 0x00000008
#define AFMT_S16_LE 0x00000010
#define AFMT_S16_BE 0x00000020
#define AFMT_S8 0x00000040
#define AFMT_U16_LE 0x00000080
#define AFMT_U16_BE 0x00000100
#define AFMT_S16_NE AFMT_S16_LE
#define AFMT_U16_NE AFMT_U16_LE
#define DSP_CAP_DUPLEX 0x00000100
#define DSP_CAP_TRIGGER 0x00001000
#define SNDCTL_DSP_SETTRIGGER ((int)0x40045010)
#define PCM_ENABLE_OUTPUT 0x00000002
#define DSP_CAP_REALTIME 0x00000200
#endif
