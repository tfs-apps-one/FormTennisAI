package tfsapps.formtennisai.model;

import com.google.mlkit.vision.pose.Pose;

/**
 * Small immutable holder bundling one analyzed camera frame's ML Kit
 * {@link Pose} together with the image dimensions it was detected in
 * (needed to scale landmark coordinates onto the overlay view's canvas).
 *
 * Baseball's CameraViewModel presumably has an equivalent internal type
 * (CameraActivity observes {@code viewModel.getPoseFrame()} and reads
 * {@code frame.pose / frame.imageWidth / frame.imageHeight}), but that
 * ViewModel wasn't among the reference files we were given, so this is
 * written fresh for {@link tfsapps.formtennisai.viewmodel.TennisCameraViewModel}.
 */
public class PoseFrame {
    public final Pose pose;
    public final int imageWidth;
    public final int imageHeight;

    public PoseFrame(Pose pose, int imageWidth, int imageHeight) {
        this.pose = pose;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }
}
