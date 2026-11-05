/// Boxes to quads with the vanilla unwrap, in world blocks, feet at y = 0.
///
/// Spaces, as the client stacks them:
/// - part space: model pixels, Y down, relative to the part's pivot;
/// - model space ([mcRigPartMatrices]): blocks, Y down, origin 24 px above
///   the feet, which is the `PoseStack` after `LivingEntityRenderer` flips it
///   with `scale(-1, -1, 1)` and `translate(0, -1.501, 0)`;
/// - rig space ([mcRigModelToWorld]): blocks, Y up, feet at the origin,
///   facing +Z (Minecraft yaw 0), after the client's `180 - bodyRot` turn.
///
/// Corners wind clockwise seen from outside, the same convention as
/// `mc_mesh.dart`: the GL pass leaves `CULL_FACE` off. Adapted from Gloss
/// Web's rig mesher.
library;

import '../mesh/mc_mesh.dart';
import '../scene/mc_math.dart';
import 'mc_rig.dart';

const double mcRigFeetPx = 24;

/// Model space to rig space: `(x, 1.5 - y, -z)`, then the rig's scale about
/// the feet.
McMat4 mcRigModelToWorld(McRig rig) => McMat4.scale(rig.scale, rig.scale, rig.scale)
    .multiply(McMat4.translation(0, mcRigFeetPx / 16, 0))
    .multiply(McMat4.scale(1, -1, -1));

/// Every visible part's transform from part space (in blocks) to model space.
/// A hidden part is absent, and so is everything under it.
Map<String, McMat4> mcRigPartMatrices(McRig rig, McRigPose pose) {
  final Map<String, McMat4> out = <String, McMat4>{};
  void walk(McRigPart part, McMat4 parent) {
    if (pose.hidden.contains(part.name)) return;
    final McVec3 pivot = pose.pivotOf(part);
    final McVec3 rot = pose.rotationOf(part);
    McMat4 local = parent
        .multiply(McMat4.translation(pivot.x / 16, pivot.y / 16, pivot.z / 16))
        .multiply(McMat4.rotationZ(rot.z))
        .multiply(McMat4.rotationY(rot.y))
        .multiply(McMat4.rotationX(rot.x));
    if (part.scale != 1) local = local.multiply(McMat4.scale(part.scale, part.scale, part.scale));
    out[part.name] = local;
    for (final McRigPart child in part.children) {
      walk(child, local);
    }
  }

  for (final McRigPart part in rig.parts) {
    walk(part, McMat4.identity());
  }
  return out;
}

/// The whole rig in rig space; [tint] multiplies every texel (dyed leather).
McMesh mcMeshRig(McRig rig, {McRigPose pose = McRigPose.rest, List<double> tint = const <double>[1, 1, 1]}) {
  final McMeshBuilder builder = McMeshBuilder();
  final Map<String, McMat4> parts = mcRigPartMatrices(rig, pose);
  final McMat4 toWorld = mcRigModelToWorld(rig);
  for (final McRigPart part in rig.allParts) {
    final McMat4? matrix = parts[part.name];
    if (matrix == null) continue;
    final McMat4 local = toWorld.multiply(matrix);
    for (final McBox box in part.boxes) {
      _box(builder, rig, box, local, tint);
    }
  }
  return builder.build();
}

void _box(McMeshBuilder builder, McRig rig, McBox box, McMat4 local, List<double> tint) {
  final double i = box.inflate;
  final double y0 = box.origin.y - i, z0 = box.origin.z - i;
  final double y1 = box.origin.y + box.size.y + i, z1 = box.origin.z + box.size.z + i;
  // `ModelPart.Cube` mirrors by swapping the two X bounds and keeping every
  // polygon's UV pairing, so the box is reflected through its own YZ plane.
  final double x0 = box.mirror ? box.origin.x + box.size.x + i : box.origin.x - i;
  final double x1 = box.mirror ? box.origin.x - i : box.origin.x + box.size.x + i;
  final double w = box.size.x, h = box.size.y, d = box.size.z;
  final double tw = rig.textureWidth.toDouble(), th = rig.textureHeight.toDouble();
  McUvRect uv(double u, double v, double uw, double vh) => McUvRect(u / tw, v / th, (u + uw) / tw, (v + vh) / th);
  // The client's `Cube` hands the bottom polygon its V bounds reversed.
  McUvRect uvFlipV(double u, double v, double uw, double vh) => McUvRect(u / tw, (v + vh) / th, (u + uw) / tw, v / th);
  List<double> world(double x, double y, double z) {
    final McVec3 p = local.transformPoint(McVec3(x / 16, y / 16, z / 16));
    return <double>[p.x, p.y, p.z];
  }

  void face(List<List<double>> corners, McUvRect r, double shade, {bool flipU = false}) {
    final List<List<double>> uvs = flipU
        ? <List<double>>[
            <double>[r.u1, r.v0],
            <double>[r.u0, r.v0],
            <double>[r.u0, r.v1],
            <double>[r.u1, r.v1],
          ]
        : <List<double>>[
            <double>[r.u0, r.v0],
            <double>[r.u1, r.v0],
            <double>[r.u1, r.v1],
            <double>[r.u0, r.v1],
          ];
    builder.quad(corners, uvs, shade, tint);
  }

  // Vanilla unwrap (`ModelPart.Cube`): top at (u+d, v), bottom at (u+d+w, v)
  // with its V reversed, then the row at v+d: west (u, d wide), north (u+d,
  // w), east (u+d+w, d), south (u+2d+w, w). West is min X, the entity's
  // right. The client hands each side polygon its first corner the larger U,
  // hence `flipU`.
  final double u = box.u, v = box.v;
  face(<List<double>>[world(x0, y0, z1), world(x1, y0, z1), world(x1, y0, z0), world(x0, y0, z0)], uv(u + d, v, w, d), 1.0);
  face(<List<double>>[world(x0, y1, z0), world(x1, y1, z0), world(x1, y1, z1), world(x0, y1, z1)], uvFlipV(u + d + w, v, w, d), 0.5);
  face(<List<double>>[world(x1, y0, z1), world(x1, y0, z0), world(x1, y1, z0), world(x1, y1, z1)], uv(u + d + w, v + d, d, h), 0.6, flipU: true);
  face(<List<double>>[world(x1, y0, z0), world(x0, y0, z0), world(x0, y1, z0), world(x1, y1, z0)], uv(u + d, v + d, w, h), 0.8, flipU: true);
  face(<List<double>>[world(x0, y0, z0), world(x0, y0, z1), world(x0, y1, z1), world(x0, y1, z0)], uv(u, v + d, d, h), 0.6, flipU: true);
  face(<List<double>>[world(x0, y0, z1), world(x1, y0, z1), world(x1, y1, z1), world(x0, y1, z1)], uv(u + 2 * d + w, v + d, w, h), 0.8, flipU: true);
}
