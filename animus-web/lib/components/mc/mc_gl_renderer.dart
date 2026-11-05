/// Scene nodes to draw calls for the NPC stage.
///
/// Per frame: clear to the sky, the grass patch, then every node with alpha
/// test 0.1, in the order the scene lists them (armour layers after the
/// body, so `LEQUAL` lets a layer win on shared faces). A glinting node
/// draws with a violet overlay. Meshes are cached per node signature and GPU
/// buffers per scene key; a node whose signature is unchanged only moves. A
/// node whose texture is still decoding is retried on the next frame the
/// stage draws. Adapted from Gloss Web's renderer without its block world,
/// sky, water and glow passes.
library;

import 'dart:js_interop';

import 'package:web/web.dart' as web;

import '../../mc/assets/mc_display_pose.dart';
import '../../mc/assets/mc_model_json.dart';
import '../../mc/assets/mc_model_resolver.dart';
import '../../mc/assets/mc_tint.dart';
import '../../mc/mesh/mc_element_mesher.dart';
import '../../mc/mesh/mc_generated_mesher.dart';
import '../../mc/mesh/mc_mesh.dart';
import '../../mc/rigs/mc_rig_mesher.dart';
import '../../mc/scene/mc_camera.dart';
import '../../mc/scene/mc_math.dart';
import '../../mc/scene/mc_scene.dart';
import 'mc_asset_loader.dart';
import 'mc_gl.dart';

const double _cutoff = 0.1;
const double _fogOff = 1e6;
const List<double> _sky = <double>[0.62, 0.75, 0.96];
const List<double> _noOverlay = <double>[0, 0, 0, 0];
const List<double> _glint = <double>[0.62, 0.32, 0.95, 0.32];
const List<double> _noFlat = <double>[0, 0, 0, 0];

/// Half the grass patch's side, blocks.
const int mcStageGroundHalf = 6;

final class _Drawable {
  _Drawable(this.buffer, this.texture, this.signature, this.image);
  final McGlBuffer buffer;
  final McGlTexture texture;
  final String signature;

  /// The image [texture] was uploaded from: a replaced skin re-uploads.
  final web.HTMLImageElement? image;
  McMat4 model = McMat4.identity();
  bool glint = false;
}

final class McGlRenderer {
  McGlRenderer(this._gl, this._pack) {
    _gl.onRestored(_rebuildAll);
    _uploadStatics();
  }

  final McGl _gl;
  final McAssetPackData _pack;
  final Map<String, _Drawable> _nodes = <String, _Drawable>{};
  final Map<String, McMesh> _meshCache = <String, McMesh>{};
  final Map<String, (web.HTMLImageElement, McGlTexture)> _textures = <String, (web.HTMLImageElement, McGlTexture)>{};
  late McGlTexture _blocks;
  late McGlTexture _items;
  late McGlBuffer _ground;

  /// Keys drawn last frame; a scene change that drops one frees it.
  int get drawn => _nodes.length;

  void _uploadStatics() {
    _blocks = _gl.texture(_pack.blocksImage);
    _items = _gl.texture(_pack.itemsImage);
    _textures.clear();
    _ground = _gl.upload(_groundMesh(_pack.manifest.blocks.uv('block/grass_block_top') ?? McUvRect.missing));
  }

  void _rebuildAll() {
    _nodes.clear();
    _uploadStatics();
  }

  McGlTexture? _texture(String id) {
    if (!_pack.hasImage(id)) {
      _pack.ensure(id);
      return null;
    }
    final web.HTMLImageElement image = _pack.image(id);
    final (web.HTMLImageElement, McGlTexture)? cached = _textures[id];
    if (cached != null && identical(cached.$1, image)) return cached.$2;
    final McGlTexture texture = _gl.texture(image);
    _textures[id] = (image, texture);
    return texture;
  }

  /// Draws one frame. Returns the number of scene nodes still waiting for a
  /// texture or model, so the stage can say what is missing.
  int render(McScene scene, McCamera camera, {required double widthPx, required double heightPx, required double dpr}) {
    if (_gl.lost) return 0;
    final web.WebGL2RenderingContext gl = _gl.gl;
    final int w = (widthPx * dpr).round(), h = (heightPx * dpr).round();
    if (w <= 0 || h <= 0) return 0;
    if (_gl.canvas.width != w) _gl.canvas.width = w;
    if (_gl.canvas.height != h) _gl.canvas.height = h;
    gl.viewport(0, 0, w, h);
    final int waiting = _sync(scene);

    gl.enable(web.WebGL2RenderingContext.DEPTH_TEST);
    gl.depthFunc(web.WebGL2RenderingContext.LEQUAL);
    gl.disable(web.WebGL2RenderingContext.CULL_FACE);
    gl.enable(web.WebGL2RenderingContext.BLEND);
    gl.blendFunc(web.WebGL2RenderingContext.SRC_ALPHA, web.WebGL2RenderingContext.ONE_MINUS_SRC_ALPHA);
    gl.depthMask(true);
    gl.clearColor(_sky[0], _sky[1], _sky[2], 1);
    gl.clear(web.WebGL2RenderingContext.COLOR_BUFFER_BIT | web.WebGL2RenderingContext.DEPTH_BUFFER_BIT);

    final McGlProgram p = _gl.program;
    gl.useProgram(p.program);
    gl.uniformMatrix4fv(p.uniforms['uProjection'], false, mcGlProjection(widthPx: widthPx, heightPx: heightPx).toFloat32().toJS);
    gl.uniformMatrix4fv(p.uniforms['uView'], false, camera.view().toFloat32().toJS);
    gl.uniform3f(p.uniforms['uFogColor'], _sky[0], _sky[1], _sky[2]);
    gl.uniform1f(p.uniforms['uFogStart'], _fogOff);
    gl.uniform1f(p.uniforms['uFogEnd'], _fogOff + 1);
    gl.uniform1i(p.uniforms['uTexture'], 0);
    gl.activeTexture(web.WebGL2RenderingContext.TEXTURE0);

    _setDraw(McMat4.identity(), _noOverlay);
    gl.bindTexture(web.WebGL2RenderingContext.TEXTURE_2D, _blocks.texture);
    _gl.draw(_ground);

    for (final McSceneNode node in scene.nodes) {
      final _Drawable? d = _nodes[node.key];
      if (d == null) continue;
      _setDraw(d.model, d.glint ? _glint : _noOverlay);
      gl.bindTexture(web.WebGL2RenderingContext.TEXTURE_2D, d.texture.texture);
      _gl.draw(d.buffer);
    }
    return waiting;
  }

  void _setDraw(McMat4 model, List<double> overlay) {
    final web.WebGL2RenderingContext gl = _gl.gl;
    final McGlProgram p = _gl.program;
    gl.uniformMatrix4fv(p.uniforms['uModel'], false, model.toFloat32().toJS);
    gl.uniform4f(p.uniforms['uOverlay'], overlay[0], overlay[1], overlay[2], overlay[3]);
    gl.uniform4f(p.uniforms['uFlat'], _noFlat[0], _noFlat[1], _noFlat[2], _noFlat[3]);
    gl.uniform1f(p.uniforms['uOpacity'], 1);
    gl.uniform1f(p.uniforms['uAlphaCutoff'], _cutoff);
  }

  int _sync(McScene scene) {
    for (final String key in _nodes.keys.toList()) {
      if (!scene.byKey.containsKey(key)) _gl.free(_nodes.remove(key)!.buffer);
    }
    int waiting = 0;
    for (final McSceneNode node in scene.nodes) {
      _Drawable? d = _nodes[node.key];
      final String? runtime = switch (node) {
        McRigNode(:final String textureId) => textureId,
        McModelNode(:final String textureId) => textureId,
        _ => null,
      };
      final web.HTMLImageElement? image = runtime != null && _pack.hasImage(runtime) ? _pack.image(runtime) : null;
      if (d == null || d.signature != node.signature || (runtime != null && !identical(d.image, image))) {
        if (d != null) _gl.free(_nodes.remove(node.key)!.buffer);
        d = _build(node);
        if (d == null) {
          waiting++;
          continue;
        }
        _nodes[node.key] = d;
      }
      d.model = node.transform;
      d.glint = node.glint;
    }
    return waiting;
  }

  _Drawable? _build(McSceneNode node) {
    switch (node) {
      case McRigNode(:final String textureId):
        final McGlTexture? texture = _texture(textureId);
        if (texture == null) return null;
        final McMesh mesh = _meshCache.putIfAbsent(node.signature, () => mcMeshRig(node.rig, pose: node.pose, tint: node.tint));
        return _Drawable(_gl.upload(mesh), texture, node.signature, _pack.image(textureId));
      case McItemNode(:final String itemId, :final McDisplayPose pose, :final bool leftHand):
        final McItemModelResult result = _pack.resolver.resolveItem(itemId);
        if (result is! McItemModelResolved) return null;
        final bool itemAtlas = mcUsesItemAtlas(result.model);
        final McMesh mesh = _meshCache.putIfAbsent(node.signature, () {
          final McMesh raw = result.model.generated
              ? mcMeshGenerated(
                  <McGeneratedLayer>[
                    for (int i = 0; i < result.model.layers.length; i++)
                      McGeneratedLayer(
                        _pack.pixels(result.model.layers[i]),
                        _pack.uvAny(result.model.layers[i]) ?? McUvRect.missing,
                        i < result.tints.length ? i : null,
                      ),
                  ],
                  tint: mcTintLookup(result.tints),
                )
              : mcMeshElements(
                  result.model,
                  uv: itemAtlas ? _pack.manifest.items.uv : _pack.manifest.blocks.uv,
                  tint: mcTintLookup(result.tints),
                );
          return raw.transformed(mcItemDisplayMatrix(result.model, pose, leftHand: leftHand));
        });
        if (mesh.isEmpty) return null;
        return _Drawable(_gl.upload(mesh), itemAtlas ? _items : _blocks, node.signature, null);
      case McBlockNode(:final String blockKey):
        final McResolvedModel? model = _pack.resolver.resolveBlock(blockKey);
        if (model == null || model.elements.isEmpty) return null;
        final McBlockstateVariantJson? variant = _pack.resolver.blockVariant(blockKey);
        final McMesh mesh = _meshCache.putIfAbsent(node.signature, () {
          final McMesh raw = mcMeshElements(
            model,
            uv: _pack.manifest.blocks.uv,
            tint: (int? index) => mcTintForBlock(blockKey, index),
          );
          if (variant == null || (variant.x == 0 && variant.y == 0)) return raw;
          // Blockstate turns are clockwise seen from +X and from above.
          return raw.transformed(McMat4.translation(0.5, 0.5, 0.5)
              .multiply(McMat4.rotationY(-variant.y.toDouble()))
              .multiply(McMat4.rotationX(-variant.x.toDouble()))
              .multiply(McMat4.translation(-0.5, -0.5, -0.5)));
        });
        return _Drawable(_gl.upload(mesh), _blocks, node.signature, null);
      case McModelNode(:final String textureId, :final McResolvedModel model):
        final McGlTexture? texture = _texture(textureId);
        if (texture == null) return null;
        final McMesh mesh = _meshCache.putIfAbsent(
          node.signature,
          () => mcMeshElements(model, uv: (String _) => const McUvRect(0, 0, 1, 1), tint: (int? _) => mcTintNone),
        );
        return _Drawable(_gl.upload(mesh), texture, node.signature, _pack.image(textureId));
    }
  }

  /// Grass tops over a square patch, the y = 0 plane, plains-tinted.
  static McMesh _groundMesh(McUvRect r) {
    final McMeshBuilder b = McMeshBuilder();
    for (int z = -mcStageGroundHalf; z < mcStageGroundHalf; z++) {
      for (int x = -mcStageGroundHalf; x < mcStageGroundHalf; x++) {
        final double x0 = x.toDouble(), x1 = x + 1.0, z0 = z.toDouble(), z1 = z + 1.0;
        b.quad(
          <List<double>>[
            <double>[x0, 0, z0],
            <double>[x1, 0, z0],
            <double>[x1, 0, z1],
            <double>[x0, 0, z1],
          ],
          <List<double>>[
            <double>[r.u0, r.v0],
            <double>[r.u1, r.v0],
            <double>[r.u1, r.v1],
            <double>[r.u0, r.v1],
          ],
          ((x + z) & 1) == 0 ? 1 : 0.94,
          mcTintGrass,
        );
      }
    }
    return b.build();
  }
}
