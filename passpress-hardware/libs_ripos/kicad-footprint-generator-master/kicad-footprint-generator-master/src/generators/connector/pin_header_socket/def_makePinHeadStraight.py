#!/usr/bin/env python

from math import sqrt

from KicadModTree import (
    Footprint,
    FootprintType,
    Line,
    Model,
    Pad,
    PadArray,
    PolygonLine,
    Property,
    Rectangle,
    Text,
)
from kilibs.geom import Vec2DCompatible, Vector2D, GeomLine
from .spec import FPconfiguration
import generators.tools.footprint.drawing_tools as DT
from generators.tools.footprint.save_footprint import write_footprint
from .temp_tools import Pad2DArrayFromPads, DrawPinArray
from kilibs.config import global_config as GC


# THT Straight (Vertical) Pinheader:
#####################################
# <--------------> body_width
#    <--------> row_pitch
# +--------------+
# | OOO      OOO |     ^
# | OOO ==== OOO |  ^  pin_width
# | OOO      OOO |  |  v
# +--------------+  pin_pitch
# | OOO      OOO |  |
# | OOO ==== OOO |  v
# | OOO      OOO |
# +--------------+
#
def makePinHeadStraight(cfg: FPconfiguration, generator_name: str):
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
    silk_grid = 1e-6
    crt_offset = gc.get_courtyard_offset(GC.GlobalConfig.CourtyardType.CONNECTOR) # 0.5
    crt_grid = gc.courtyard_grid # 0.01

    # --- init kicad footprint (SMD origin at center, THT at pin 1):
    kicad_mod = Footprint(cfg.footpr_name, cfg.footpr_type)
    kicad_mod.description = cfg.getDescription()
    #if cfg.isSocket and cfg.datasheet != None:
    #    kicad_mod.description += " (" + cfg.datasheet + "), script generated"
    kicad_mod.tags = cfg.getBaseTags()

    # --- Calculate pads center and pin1 offset from origin:
    half_rows_x = (cfg.row_count - 1) / 2 * cfg.row_pitch
    half_posn_y = (cfg.pos_count - 1) / 2 * cfg.pin_pitch
    pad = Vector2D(cfg.pads_length, cfg.pads_width) # x=length, y=width

    # if cfg.mount_type == "SMD" and cfg.pin1_left:
    #     p1offset = Vector2D(-half_rows_x, -half_posn_y)
    #     pads_c = Vector2D(0, 0)
    # elif cfg.mount_type == "SMD" and not cfg.pin1_left:
    #     p1offset = Vector2D(half_rows_x, -half_posn_y)
    #     pads_c = Vector2D(0, 0)
    if cfg.pin1_left: # THT
        p1offset = Vector2D(0, 0)
        pads_c = Vector2D(half_rows_x, half_posn_y)
    else: # THT
        p1offset = Vector2D(0, 0)
        pads_c = Vector2D(-half_rows_x, half_posn_y)

    # --- Calculate body, fabrication, silk and courtyard dimensions/positions:
    # anchor for SMD-footprints is in the center, for THT-footprints at pin1
    # See Abbreviations at the top.

    # body_overlength is symetrical but keep separated as top/bottom internally.
    overlen_top = cfg.pin_pitch / 2 + cfg.body_overlength
    overlen_bot = cfg.pin_pitch / 2 + cfg.body_overlength

    fabb_h = (cfg.pos_count - 1) * cfg.pin_pitch + overlen_top + overlen_bot
    fabb_w = cfg.body_width
    fabb_t = -(cfg.pin_pitch / 2) - cfg.body_overlength
    #if cfg.mount_type == "SMD":
    #    fabb_t = -(h_fab/2.0) # in case top/bot overlength are symmetrical
    #    fabb_l = -(cfg.body_width/2.0) + cfg.body_offset # - body_offset for sockets?
    if cfg.isSocket:
        fabb_l = -(cfg.body_width / 2) - half_rows_x - cfg.body_offset
    else:
        fabb_l = -(cfg.body_width / 2) + half_rows_x + cfg.body_offset
    fabb_b = fabb_t + fabb_h
    fabb_r = fabb_l + fabb_w
    if cfg.isSocket:
        fabb_c = pads_c - [cfg.body_offset, 0] # PinSockets are drawn to the left.
    else:
        fabb_c = pads_c + [cfg.body_offset, 0] # PinHeaders/IDC are drawn to the right.
    # print(f'fabb_h:{fabb_h:.3f} w:{fabb_w:.3f} t:{fabb_t:.3f} l:{fabb_l:.3f} c:{fabb_c} overwidth:{fabb_overwidth} offset:{cfg.body_offset}')

    fab_txt_size, fab_txt_thick = gc.get_text_properties_for_layer("F.Fab").clamp_size(fabb_w * 0.6)
    # That causes diffs for 1.00mm headers/sockets, 
	# use the old unrounded calc for now.
    fab_txt_thick = fab_txt_size.y * 0.15

    slkb_h = fabb_h + 2 * silk_fab_offset
    slkb_w = fabb_w + 2 * silk_fab_offset
    slkb_t = fabb_t - silk_fab_offset
    slkb_l = fabb_l - silk_fab_offset
    slkb_b = fabb_b + silk_fab_offset
    slkb_r = fabb_r + silk_fab_offset

    crt_h = max(fabb_h, (cfg.pos_count - 1) * cfg.pin_pitch + pad.y) + 2 * crt_offset
    crt_w = max(
        cfg.body_width,
        (cfg.row_count - 1) * cfg.row_pitch + pad.x
    ) + 2 * crt_offset
    crt_t = -(crt_h / 2) + half_posn_y 
    crt_l = fabb_l - crt_offset
    crt_r = crt_l + crt_w
    crt_b = crt_t + crt_h
    crt_c = Vector2D(crt_l + crt_w / 2, crt_t + crt_h / 2)

    # --- Create pads:
    pad_1 = Pad(type=Pad.TYPE_THT, shape=Pad.SHAPE_RECT, layers=Pad.LAYERS_THT, at=[0, 0], size=pad, drill=cfg.pins_drill)
    pad_n = Pad(type=Pad.TYPE_THT, shape=Pad.SHAPE_OVAL, layers=Pad.LAYERS_THT, at=[0, 0], size=pad, drill=cfg.pins_drill)

    padlist = Pad2DArrayFromPads(p1offset, cfg.pos_count, cfg.row_count, cfg.pin_pitch, pad_n, pad_1,
        pads_offset=cfg.pads_offset, pin1_left=cfg.pin1_left, staggered=cfg.isStaggered
    )
    kicad_mod.extend(padlist)
    keepouts_silk = DT.getKeepoutsForPads(pads=padlist, clearance=silk_pad_offset) # ToDo: gc.silk_pad_clearance should be enough

    # --- set general values
    kicad_mod.append(
        Property(name=Property.REFERENCE, text='REF**', at=[pads_c.x, slkb_t - txt_offset], layer='F.SilkS'))
        # REF** should be at the center of silk instead of center of pads?
    ref_offset = cfg.pin_pitch if (cfg.isSocket and cfg.row_count > 1) else 0
    kicad_mod.append(
        Text(text='${REFERENCE}', at=[pads_c.x, crt_c.y - ref_offset], rotation=90, layer='F.Fab', size=fab_txt_size, thickness=fab_txt_thick))
    kicad_mod.append(
        Property(name=Property.VALUE, text=cfg.footpr_name, at=[pads_c.x, slkb_b + txt_offset], layer='F.Fab'))

    # --- create FAB-layer
    chamfer = fabb_w/4
    kicad_mod.append(Line(start=[fabb_l + chamfer, fabb_t], end=[fabb_r, fabb_t], layer='F.Fab', width=gc.fab_line_width))
    kicad_mod.append(Line(start=[fabb_r, fabb_t], end=[fabb_r, fabb_b], layer='F.Fab', width=gc.fab_line_width))
    kicad_mod.append(Line(start=[fabb_r, fabb_b], end=[fabb_l, fabb_b], layer='F.Fab', width=gc.fab_line_width))
    kicad_mod.append(Line(start=[fabb_l, fabb_b], end=[fabb_l, fabb_t+chamfer], layer='F.Fab', width=gc.fab_line_width))
    kicad_mod.append(Line(start=[fabb_l, fabb_t+chamfer], end=[fabb_l + chamfer, fabb_t], layer='F.Fab', width=gc.fab_line_width))

    # --- create SILKSCREEN-layer + pin1 marker
    # Silkscreen body
    body_min_x_square = pad.x / 2 + silk_pad_offset
    body_min_y_square = pad.y / 2 + silk_pad_offset

    # drawing bottom line:
    if (cfg.pos_count-1)*cfg.pin_pitch + body_min_y_square < slkb_b:
        kicad_mod.append(Line(start=[slkb_l, slkb_b], end=[slkb_r, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
    else:
        if cfg.pos_count == 1:
            kicad_mod.append(Line(start=[slkb_l, body_min_y_square], end=[slkb_r, body_min_y_square], layer='F.SilkS', width=gc.silk_line_width))
        else:
            segments = DT.applyKeepouts([GeomLine(start=[slkb_l, slkb_b], end=[slkb_r, slkb_b])], keepouts_silk)
            DT.addLinesToSilk(kicad_mod, segments, gc.silk_line_width, silk_grid)
            # body_min_x_round = sqrt((body_min_x_square * body_min_x_square - (overlen_bot + silk_fab_offset) * (overlen_bot + silk_fab_offset)))
            # kicad_mod.append(Line(start=[slkb_l, slkb_b], end=[-body_min_x_round, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
            # kicad_mod.append(Line(start=[(cfg.row_count-1)*cfg.row_pitch+body_min_x_round, slkb_b], end=[slkb_r, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
            # for x in range(0, (cfg.row_count-1)):
            #     kicad_mod.append(Line(start=[x*cfg.row_pitch+body_min_x_round, slkb_b], end=[(x+1)*cfg.row_pitch-body_min_x_round, slkb_b], layer='F.SilkS', width=gc.silk_line_width))

    # drawing sidelines
    # calculate top Y position
    if cfg.pin_pitch < body_min_y_square * 2:
        shoulder_y_pos = body_min_y_square
        shoulder_y_lines = 2
    else:
        shoulder_y_pos = cfg.pin_pitch / 2
        shoulder_y_lines = 1
    if cfg.row_pitch < body_min_x_square * 2:
        top_x_pos = body_min_x_square if cfg.pin1_left else -body_min_x_square
        top_x_lines = 2
    else:
        top_x_pos = cfg.row_pitch/2 if cfg.pin1_left else -cfg.row_pitch/2
        top_x_lines = 1
    def printshoulderinfo():
        print(f'pitch: {cfg.pin_pitch} body_min[x:{body_min_x_square:.3f}, y:{body_min_y_square:.3f}] ' +
        f'shoulder lines[x:{top_x_lines} y:{shoulder_y_lines}] pos[x:{top_x_pos:.3f}, y:{shoulder_y_pos:.3f}] ' +
        f'left/top/right:{slkb_l:.3f}/{slkb_t:.3f}/{slkb_r:.3f}')
    # printshoulderinfo()

    if (
        (not(cfg.isSocket) and slkb_r  > body_min_x_square+(cfg.row_count-1)*cfg.row_pitch) or
        (cfg.isSocket and slkb_l  < body_min_x_square-(cfg.row_count-1)*cfg.row_pitch)
    ):
        # left vertical side line: shoulder-bottom
        kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[slkb_l, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
        if cfg.row_count == 1:
            # right vertical side line: shoulder-bottom
            kicad_mod.append(Line(start=[slkb_r, shoulder_y_pos], end=[slkb_r, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
        else:
            # right vertical side line: top-bottom
            kicad_mod.append(Line(start=[slkb_r, slkb_t], end=[slkb_r, slkb_b], layer='F.SilkS', width=gc.silk_line_width))
    elif cfg.pos_count != 1:
        # left vertical side line: shoulder to bottom
        segments = DT.applyKeepouts([GeomLine(start=[slkb_l, shoulder_y_pos], end=[slkb_l, slkb_b])], keepouts_silk)
        DT.addLinesToSilk(kicad_mod, segments, gc.silk_line_width, silk_grid)
        # slkb_l_adj = slkb_l - (cfg.row_count-1) * cfg.row_pitch if isSocket else 0
        # body_min_y_round = sqrt((body_min_x_square * body_min_x_square - slkb_l * slkb_l))
        # kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[slkb_l, cfg.pin_pitch-body_min_y_round], layer='F.SilkS', width=silk_line_width))
        # kicad_mod.append(Line(start=[slkb_l, (cfg.pos_count-1)*cfg.pin_pitch+body_min_y_round], end=[slkb_l, slkb_b], layer='F.SilkS', width=silk_line_width))
        if cfg.row_count == 1:
            # right vertical side line: shoulder to bottom
            # kicad_mod.append(Line(start=[slkb_r, shoulder_y_pos], end=[slkb_r, cfg.pin_pitch-body_min_y_round], layer='F.SilkS', width=silk_line_width))
            segments = DT.applyKeepouts([GeomLine(start=[slkb_r, shoulder_y_pos], end=[slkb_r, slkb_b])], keepouts_silk)
            DT.addLinesToSilk(kicad_mod, segments, gc.silk_line_width, silk_grid)
        else:
            # right vertical side line: body_min_y_square to bottom
            # kicad_mod.append(Line(start=[slkb_r, body_min_y_square], end=[slkb_r, cfg.pin_pitch-body_min_y_round], layer='F.SilkS', width=silk_line_width))
            segments = DT.applyKeepouts([GeomLine(start=[slkb_r, body_min_y_square], end=[slkb_r, slkb_b])], keepouts_silk)
            DT.addLinesToSilk(kicad_mod, segments, silk_line_width, silk_grid)
        # kicad_mod.append(Line(start=[slkb_r, (cfg.pos_count-1)*cfg.pin_pitch+body_min_y_round], end=[slkb_r, slkb_b], layer='F.SilkS', width=silk_line_width))
        # for x in range(1, (cfg.pos_count-1)):
        #     kicad_mod.append(Line(start=[slkb_l, x*cfg.pin_pitch+body_min_y_round], end=[slkb_l, (x+1)*cfg.pin_pitch-body_min_y_round], layer='F.SilkS', width=silk_line_width))
        #     kicad_mod.append(Line(start=[slkb_r, x*cfg.pin_pitch+body_min_y_round], end=[slkb_r, (x+1)*cfg.pin_pitch-body_min_y_round], layer='F.SilkS', width=silk_line_width))

    # drawing top
    if cfg.row_count == 1:
        # shoulder horizontal line: left to right
        if shoulder_y_lines == 1:
            kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[slkb_r, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
        elif shoulder_y_lines == 2:
            top_x_round = sqrt((body_min_x_square * body_min_x_square - (shoulder_y_pos-cfg.pin_pitch) * (shoulder_y_pos-cfg.pin_pitch)))
            kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[slkb_l + slkb_w/2-top_x_round, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
            kicad_mod.append(Line(start=[slkb_l + slkb_w/2 + top_x_round, shoulder_y_pos], end=[slkb_r, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
    else:
        # shoulder horizontal line: left to top_x_pos
        if shoulder_y_lines == 1:
            kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[top_x_pos, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
        elif shoulder_y_lines == 2:
            top_x_round = sqrt((body_min_x_square * body_min_x_square - (shoulder_y_pos-cfg.pin_pitch) * (shoulder_y_pos-cfg.pin_pitch)))
            if cfg.pin1_left and top_x_pos > cfg.row_pitch-top_x_round:
                top_x_end = cfg.row_pitch-top_x_round
            elif not(cfg.pin1_left) and top_x_pos < -cfg.row_pitch + top_x_round:
                top_x_end = -cfg.row_pitch - top_x_round # ToDo: check after canvas
            else:
                top_x_end = top_x_pos
            # printshoulderinfo()
            # print(f'top_x_round: {top_x_round} top_x_end: {top_x_end}')
            if cfg.pin1_left:
                kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[-top_x_round, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
            else:
                kicad_mod.append(Line(start=[slkb_l, shoulder_y_pos], end=[top_x_end, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))
            if top_x_round*2 + gc.silk_line_width*2 < pad.x:
                kicad_mod.append(Line(start=[top_x_round, shoulder_y_pos], end=[top_x_end, shoulder_y_pos], layer='F.SilkS', width=gc.silk_line_width))

        # vertical line between row 1 and 2
        if top_x_lines == 1:
            kicad_mod.append(Line(start=[top_x_pos, shoulder_y_pos], end=[top_x_pos, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
        elif top_x_lines == 2:
            if cfg.pin1_left or cfg.row_count == 1: # ToDo: check if can be simplified to just the else
                shoulder_y_round = sqrt((body_min_x_square * body_min_x_square - (cfg.row_pitch-top_x_pos) * (cfg.row_pitch-top_x_pos)))
                if shoulder_y_pos > cfg.pin_pitch-shoulder_y_round:
                    shoulder_y_pos = cfg.pin_pitch-shoulder_y_round
                if shoulder_y_round*2 + gc.silk_line_width*2 < pad.y:
                    kicad_modg.append(Line(start=[top_x_pos, shoulder_y_pos], end=[top_x_pos, shoulder_y_round], layer='F.SilkS', width=gc.silk_line_width))
                    kicad_modg.append(Line(start=[top_x_pos, -shoulder_y_round], end=[top_x_pos, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
            else:
                shoulder_y_round = sqrt((body_min_x_square * body_min_x_square - (cfg.row_pitch+top_x_pos) * (cfg.row_pitch+top_x_pos)))
                if shoulder_y_pos < shoulder_y_round:
                    shoulder_y_pos = shoulder_y_round
                if shoulder_y_round*2 + gc.silk_line_width*2 < pad.y:
                    kicad_mod.append(Line(start=[top_x_pos, shoulder_y_pos], end=[top_x_pos, shoulder_y_round], layer='F.SilkS', width=gc.silk_line_width))
                    kicad_mod.append(Line(start=[top_x_pos, -shoulder_y_round], end=[top_x_pos, slkb_t], layer='F.SilkS', width=gc.silk_line_width))

        # highest horizontal line
        if abs(slkb_t) > body_min_y_square:
            kicad_mod.append(Line(start=[top_x_pos, slkb_t], end=[slkb_r, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
        else:
            top_x_round = sqrt((body_min_x_square * body_min_x_square - (abs(slkb_t)) * (abs(slkb_t))))
            if cfg.pin1_left:
                if top_x_pos > cfg.row_pitch-top_x_round + 2*gc.silk_line_width:
                    kicad_mod.append(Line(start=[top_x_pos, slkb_t], end=[cfg.row_pitch-top_x_round, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
                kicad_mod.append(Line(start=[cfg.row_pitch+top_x_round, slkb_t], end=[slkb_r, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
            else:
                if top_x_pos < -(cfg.row_pitch-top_x_round + 2*gc.silk_line_width):
                    kicad_mod.append(Line(start=[top_x_pos, slkb_t], end=[cfg.row_pitch-top_x_round, slkb_t], layer='F.SilkS', width=gc.silk_line_width))
                kicad_mod.append(Line(start=[top_x_round, slkb_t], end=[slkb_r, slkb_t], layer='F.SilkS', width=gc.silk_line_width))

    # pin 1 marker
    pin1_min = -body_min_x_square
    if pin1_min < slkb_l:
        pin1_x = pin1_min
    else:
        pin1_x = slkb_l
    if pin1_min < slkb_t:
        pin1_y = pin1_min
    else:
        pin1_y = slkb_t
    if cfg.isSocket and cfg.row_count>1:
        kicad_mod.append(PolygonLine(shape=[[pin1_x + slkb_w, 0], [pin1_x + slkb_w, pin1_y], [pin1_x + slkb_w - cfg.pin_pitch / 2, pin1_y]], layer='F.SilkS', width=gc.silk_line_width))
    else:
        kicad_mod.append(PolygonLine(shape=[[pin1_x, 0], [pin1_x, pin1_y], [0, pin1_y]], layer='F.SilkS', width=gc.silk_line_width))

	# --- create courtyard:
    kicad_mod.append(
        Rectangle(
            start=[DT.roundCrt(crt_l), DT.roundCrt(crt_t)],
            end=[DT.roundCrt(crt_r), DT.roundCrt(crt_b)],
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
