.class public final Lcom/agc/ReaOutputConfig;
.super Ljava/lang/Object;
.source "ReaOutputConfig.java"


# REA (LineageOS port):
#
# One UI's OutputConfiguration.semCreateOutputConfiguration(int, Surface, int, int) does
# not exist outside Samsung's framework. This drop-in replacement is what the feature's
# reflection bridge resolves instead: it builds a plain OutputConfiguration and folds
# Samsung's stream "option" (the 4th argument) into the upper bits of streamUseCase,
# exactly as One UI encoded it (option << 16). The patched cameraserver accepts those
# vendor stream options and injects the PDK parameter string itself, so no Samsung
# framework API is needed here.

.method public static semCreateOutputConfiguration(ILandroid/view/Surface;II)Landroid/hardware/camera2/params/OutputConfiguration;
    .locals 4

    new-instance v0, Landroid/hardware/camera2/params/OutputConfiguration;

    invoke-direct {v0, p1}, Landroid/hardware/camera2/params/OutputConfiguration;-><init>(Landroid/view/Surface;)V

    int-to-long v1, p3

    const/16 v3, 0x10

    shl-long/2addr v1, v3

    invoke-virtual {v0, v1, v2}, Landroid/hardware/camera2/params/OutputConfiguration;->setStreamUseCase(J)V

    return-object v0
.end method
