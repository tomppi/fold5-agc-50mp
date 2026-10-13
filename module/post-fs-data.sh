#!/system/bin/sh
# q5q_cameraserver_hr v5
#
# init refuses the exec transition into u:r:cameraserver when the entry point is a
# bind mount from /data: security_bounded_transition denied -> execute_no_trans
# denied -> the service exits 127 and there is no camera service at all. Staging the
# binary on a private tmpfs and bind-mounting from there starts normally, so this
# module mounts its own tmpfs at post-fs-data time, before init starts class main.
MODDIR=${0%/*}
BIN=$MODDIR/system/bin/cameraserver
STAGE=/mnt/rea_cameraserver
LOG=/data/adb/q5q_cam_module.log
log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> $LOG; }

log "post-fs-data(v5): start"
chmod 0755 "$BIN" 2>/dev/null

mkdir -p "$STAGE"
if ! mountpoint -q "$STAGE"; then
    mount -t tmpfs -o mode=0755 tmpfs "$STAGE" 2>/dev/null || log "tmpfs mount FAILED"
fi
if cp -f "$BIN" "$STAGE/cameraserver" 2>/dev/null; then
    chmod 0755 "$STAGE/cameraserver"
    chcon u:object_r:cameraserver_exec:s0 "$STAGE/cameraserver" 2>/dev/null
    if mount -o bind "$STAGE/cameraserver" /system/bin/cameraserver 2>/dev/null; then
        log "bind mount from tmpfs OK"
    else
        log "bind mount FAILED (rc=$?)"
    fi
else
    log "copy to tmpfs FAILED"
fi
log "label now: $(ls -Z /system/bin/cameraserver 2>/dev/null)"
