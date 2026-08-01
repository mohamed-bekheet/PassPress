#!/usr/bin/env python

from math import sqrt

from KicadModTree import (
    Footprint,
    Line,
    Model,
    Pad,
    PolygonLine,
    Property,
    Rectangle,
    Text,
)
from kilibs.geom import Vec2DCompatible, Vector2D
from .spec import FPconfiguration
import generators.tools.footprint.drawing_tools as DT
from generators.tools.footprint.save_footprint import write_footprint
from kilibs.config import global_config as GC


# THT Angled (Horizontal) PinSocket:
#####################################
# <--------------------------------------> body_width
#                                             <- ------> row_pitch
#                                          <-> body_offset
# +---------------------------------------+            ---+
# |                                       |  OOO      OOO |    ^
# |                                       |  OOO ==== OOO | ^  pin_width
# |                                       |  OOO      OOO   |  v
# +---------------------------------------+                 pin_pitch
# |                                       |  OOO      OOO   |
# |                                       |  OOO ==== OOO   v
# |                                       |  OOO      OOO
# +---------------------------------------+
#
def makeSocketStripAngled(cfg: FPconfiguration, generator_name: str):
    # Abbreviations: fab, slk and crt for fabrication, silk and coutryard.
    # fabb/fabp and slkb/slkp for drawing body and pins (b or p after fab/slk).
    # _h, _w, _t, _b, _l, _r, _c for height, width, top, bottom, left, right, center (vector)
    gc = GC.GLOBAL_CONFIG

    # assemble library and footprint name:
    cfg.lib_name = cfg.getLibraryName()
    cfg.footpr_name = cfg.getFootprintName()

    # --- Settings:
    txt_offset = 1 # similar to text_edge_offset = size / 2 + 0.2 in addTextFields()->_getTextFieldDetails()
    # fab_text properties (fab_txt_size, fab_txt_thick) are calculated/clamped further down.
    # silk_pad_offset = gc.silk_pad_offset # = clearance + line_width/2 = 0.2 + 0.06
    silk_pad_offset = gc.silk_pad_clearance + gc.silk_fab_offset # 0.2 + 0.11
    # This is set a bit further out than normal, not quite clear why.
    silk_fab_offset = gc.silk_fab_offset # 0.11
    crt_offset = gc.get_courtyard_offset(GC.GlobalConfig.CourtyardType.CONNECTOR) # 0.5

    # --- init kicad footprint (SMD origin at center, THT at pin 1):
    kicad_mod = Footprint(cfg.footpr_name, cfg.footpr_type)
    kicad_mod.description = cfg.getDescription()
    # if cfg.datasheet != None:
    #     kicad_mod.description += " (" + cfg.datasheet + "), script generated"
    kicad_mod.tags = cfg.getBaseTags()

    # --- Calculate pads center and pin1 offset from origin:
    half_rows_x = (cfg.row_count - 1) / 2 * cfg.row_pitch
    half_posn_y = (cfg.pos_count - 1) / 2 * cfg.pin_pitch
    pad = Vector2D(cfg.pads_length, cfg.pads_width) # x=length, y=width

    #if cfg.pin1_left: # THT
    # This works only for THT pinheaders
    #    pads_c = Vector2D(half_rows_x, half_posn_y)
    #elif cfg.isSocket: # THT
    pads_c = Vector2D(-half_rows_x, half_posn_y)

    # --- Calculate body, fabrication, silk and courtyard dimensions/positions:
    # anchor for SMD-footprints is in the center, for THT-footprints at pin1
    # See Abbreviations at the top.

    fabb_h = (cfg.pos_count - 1) * cfg.pin_pitch + cfg.pin_pitch / 2 + cfg.pin_pitch / 2
    fabb_w = cfg.body_width
    fabb_t = -(cfg.pin_pitch / 2) - cfg.body_overlength
    fabb_l = -(cfg.row_count - 1) * cfg.row_pitch - cfg.body_offset - cfg.body_width # should this not be row_count-1.5?
    fabb_b = fabb_t + fabb_h
    fabb_r = fabb_l + cfg.body_width
    fabb_c = Vector2D(fabb_l + (fabb_w / 2), fabb_t + (fabb_h / 2))
	
    fabp_t = -cfg.pins_width / 2

    slkb_h = fabb_h + 2 * silk_fab_offset
    slkb_w = fabb_w + 2 * silk_fab_offset
    slkb_t = fabb_t - silk_fab_offset
    slkb_l = fabb_l - silk_fab_offset
    slkb_b = fabb_b + silk_fab_offset
    slkb_r = fabb_r + silk_fab_offset

    slkp_t = fabp_t - silk_fab_offset
    slkp_r = slkb_r - slkb_w

    crt_h = fabb_h + 2 * crt_offset
    crt_w = (cfg.row_count - 1) * cfg.row_pitch + 2 * crt_offset
    crt_w = crt_w + cfg.row_pitch / 2 + cfg.body_offset + cfg.body_width
    crt_t = -cfg.pin_pitch / 2 - crt_offset
    crt_b = crt_t + crt_h
    crt_l = -crt_w + cfg.row_pitch / 2 + crt_offset 
    crt_r = crt_l + crt_w
    crt_c = Vector2D(crt_l + crt_w / 2, crt_t + crt_h / 2)

    # --- Create pads:
    x1 = 0
    y1 = 0

    pad_type = Pad.TYPE_THT
    pad_shape1 = Pad.SHAPE_RECT
    pad_shapeother = Pad.SHAPE_OVAL
    pad_layers = Pad.LAYERS_THT

    p = 1
    for pos in range(1, cfg.pos_count + 1):
        x1 = 0
        for row in range(1, cfg.row_count + 1):
            if p == 1:
                kicad_mod.append(Pad(number=p, type=pad_type, shape=pad_shape1, at=[x1, y1], size=pad, drill=cfg.pins_drill,
                                      layers=pad_layers))
            else:
                kicad_mod.append(
                    Pad(number=p, type=pad_type, shape=pad_shapeother, at=[x1, y1], size=pad, drill=cfg.pins_drill,
                        layers=pad_layers))

            p += 1
            x1 -= cfg.row_pitch

        y1 += cfg.pin_pitch

    # --- set general values
    kicad_mod.append(
        Property(name=Property.REFERENCE, text='REF**', at=[crt_c.x, crt_t - crt_offset], layer='F.SilkS'))
    kicad_mod.append(
        Text(text='${REFERENCE}', at=[crt_c.x, crt_t - crt_offset], layer='F.Fab'))
    kicad_mod.append(
        Property(name=Property.VALUE, text=cfg.footpr_name, at=[crt_c.x, crt_b + crt_offset], layer='F.Fab'))

    # --- create FAB-layer
    y1 = fabb_t
    yp = fabp_t
    for pos in range(1, cfg.pos_count + 1):
        kicad_mod.append(Rectangle(start=[fabb_r, y1], end=[fabb_l, y1 + cfg.pin_pitch], layer='F.Fab', width=gc.fab_line_width))
        kicad_mod.append(
            Rectangle(start=[0, yp], end=[fabb_r , yp + cfg.pins_width], layer='F.Fab', width=gc.fab_line_width))
        y1 = y1 + cfg.pin_pitch
        yp = yp + cfg.pin_pitch

    # --- create SILKSCREEN-layer + pin1 marker
    y1 = slkb_t
    yp = slkp_t
    for pos in range(1, cfg.pos_count + 1):
        if cfg.pos_count == 1 and pos == 1:
            kicad_mod.append(
                Rectangle(start=[slkb_r, y1], end=[slkp_r, y1 + cfg.pin_pitch + 2 * gc.silk_fab_offset], layer='F.SilkS',
                         width=gc.silk_line_width))
        if (pos == 1 or pos == cfg.pos_count):
            kicad_mod.append(Rectangle(start=[slkb_r, y1], end=[slkp_r, y1 + cfg.pin_pitch + silk_fab_offset], layer='F.SilkS',
                                       width=gc.silk_line_width))
            y1 = y1 + silk_fab_offset
        else:
            kicad_mod.append(Rectangle(start=[slkb_r, y1], end=[slkp_r, y1 + cfg.pin_pitch], layer='F.SilkS', width=gc.silk_line_width))

        kicad_mod.append(Line(start=[-((cfg.row_count - 1) * cfg.row_pitch + pad.x / 2 + silk_fab_offset+gc.silk_line_width), yp], end=[slkb_r, yp], layer='F.SilkS',width=gc.silk_line_width))
        kicad_mod.append(Line(start=[-((cfg.row_count - 1) * cfg.row_pitch + pad.x / 2 + silk_fab_offset+gc.silk_line_width), yp + cfg.pins_width + 2 * silk_fab_offset],end=[slkb_r, yp + cfg.pins_width + 2 * silk_fab_offset], layer='F.SilkS', width=gc.silk_line_width))
        if cfg.row_count > 1:
            for row in range(2, cfg.row_count + 1):
                kicad_mod.append(Line(start=[-((row - 2) * cfg.row_pitch + pad.x / 2 + silk_fab_offset+gc.silk_line_width), yp],
                                       end=[-((row - 1) * cfg.row_pitch - pad.x / 2 - silk_fab_offset-gc.silk_line_width), yp], layer='F.SilkS',
                                       width=gc.silk_line_width))
                kicad_mod.append(
                    Line(start=[-((row - 2) * cfg.row_pitch + pad.x / 2 + silk_fab_offset+gc.silk_line_width), yp + cfg.pins_width + 2 * silk_fab_offset],
                         end=[-((row - 1) * cfg.row_pitch - pad.x / 2 - silk_fab_offset-gc.silk_line_width), yp + cfg.pins_width + 2 * silk_fab_offset],
                         layer='F.SilkS', width=gc.silk_line_width))
        if pos == 1:
            y = y1 + gc.silk_line_width
            while y < y1 + cfg.pin_pitch + 2 * silk_fab_offset:
                kicad_mod.append(Line(start=[slkb_r, y], end=[slkp_r, y], layer='F.SilkS', width=gc.silk_line_width))
                y = y + gc.silk_line_width
        y1 = y1 + cfg.pin_pitch
        yp = yp + cfg.pin_pitch

    kicad_mod.append(PolygonLine(shape=[[0, -cfg.pin_pitch / 2], [cfg.pin_pitch / 2, -cfg.pin_pitch / 2], [cfg.pin_pitch / 2, 0]], layer='F.SilkS', width=gc.silk_line_width))

	# --- create courtyard:
    kicad_mod.append(
        Rectangle(
            start=[DT.roundCrt(crt_r), DT.roundCrt(crt_t)],
            end=[DT.roundCrt(crt_l), DT.roundCrt(crt_b)],
            layer='F.CrtYd',
            width=gc.courtyard_line_width
        )
    )

    # --- add model
    kicad_mod.append(
        Model(
            filename=gc.model_3d_prefix
            + cfg.lib_name
            + ".3dshapes/"
            + cfg.footpr_name
            + gc.model_3d_suffix
        )
    )

    write_footprint(kicad_mod, cfg.lib_name, generator_name)

