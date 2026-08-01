# generators is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either
# version 3 of the License, or (at your option) any later version.
#
# generators is distributed in the hope that it will be useful, but WITHOUT ANY
# WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
# PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with
# generators. If not, see < http://www.gnu.org/licenses/ >.
#
# (C) The KiCad Librarian Team

import logging
import math

from KicadModTree import (
    Footprint,
    FootprintType,
    Pad,
    PolygonLine,
    Property,
    Rectangle,
    ReferencedPad,
    RoundRadiusHandler,
    Text,
)
from .spec import (
    GridArraySpec,
    LayoutData,
)
from kilibs.geom import Direction, Vector2D
from generators.tools.footprint.declarative_def_tools import (
    ast_evaluator,
    fp_additional_drawing,
)
from generators.tools.footprint.save_footprint import write_footprint
from kilibs.config import global_config as GC
from generators.tools.footprint.nodes import pin1_arrow


def create_footprints(spec: GridArraySpec, generator_name: str) -> int:
    """Create the footprint(s) corresponding to the spec.

    Args:
        spec: the grid array specification.

    Returns:
        The number of footprints generated.
    """
    if not spec.has_fp_data:
        return 0

    if "pad_diameter" in spec.spec:
        pad_diameter = spec.spec["pad_diameter"]
        logging.debug(
            f"Pad size of {id} is set by the footprint definition. "
            "This should only be done for manufacturer-specific footprints."
        )
    elif "ball_type" in spec.spec and "ball_diameter" in spec.spec:
        ball_diameter = spec.spec["ball_diameter"]
        ball_type = spec.spec["ball_type"]
        # IPC-7352 Table 3-11 Median (Nominal) Material Level B
        if ball_type == "collapsible":
            pad_diameter = round(0.8 * ball_diameter, 2)
        elif ball_type == "non-collapsible":
            pad_diameter = round(1.1 * ball_diameter, 2)
        else:
            raise KeyError(
                f"{id}: '{ball_type}' is an invalid ball type. Only "
                "'collapsible' and 'non-collapsible' are accepted values. "
                "Aborting."
            )
    elif "ball_type" in spec.spec and "ball_diameter" not in spec.spec:
        raise KeyError(f"{id}: Ball diameter is missing. Aborting.")
    elif "ball_diameter" in spec.spec and "ball_type" not in spec.spec:
        raise KeyError(f"{id}: Ball type is missing. Aborting.")
    else:
        raise KeyError(
            f"{id}: The config file must include 'ball_type' and "
            "'ball_diameter' or 'pad_diameter'. Aborting."
        )
    spec.spec["pad_size"] = [pad_diameter, pad_diameter]
    _create_footprint_variant(spec, generator_name)
    return 1

def _create_footprint_variant(config: GridArraySpec, generator_name: str) -> None:
    # Pull out the old-style parameter dictionary
    spec = config.spec

    evaluator_params = {"pitch": config.pitch}

    fp_evaluator = ast_evaluator.ASTevaluator(symbols=evaluator_params)  # pyright: ignore

    pkg_x = config.body_size_x
    pkg_y = config.body_size_y
    f_fab_ref_rot = 0.0

    f = Footprint(config.name, FootprintType.SMD)
    if "mask_margin" in spec:
        f.setMaskMargin(spec["mask_margin"])
    if "paste_margin" in spec:
        f.setPasteMargin(spec["paste_margin"])
    if "paste_ratio" in spec:
        f.setPasteMarginRatio(spec["paste_ratio"])

    s1 = [1.0, 1.0]
    if pkg_x < 4.3 and pkg_y > pkg_x:
        s2 = [
            min(1.0, round(pkg_y / 4.3, 2))
        ] * 2  # Y size is greater, so rotate F.Fab reference
        f_fab_ref_rot = -90.0
    else:
        s2 = [min(1.0, round(pkg_x / 4.3, 2))] * 2

    t1 = 0.15 * s1[0]
    t2 = 0.15 * s2[0]

    chamfer = GC.GLOBAL_CONFIG.fab_bevel.get_chamfer_size(min(pkg_x, pkg_y))

    crtYdOffset = GC.GLOBAL_CONFIG.get_courtyard_offset(
        GC.GlobalConfig.CourtyardType.BGA
    )

    def crt_round(x: float) -> float:
        # Round away from zero for proper courtyard calculation
        neg = x < 0
        if neg:
            x = -x
        x = math.ceil(x * 100) / 100.0
        if neg:
            x = -x
        return x

    pitchX, pitchY, staggered = config.calculate_stagger()

    xCenter = 0.0
    xLeftFab = xCenter - pkg_x / 2.0
    xRightFab = xCenter + pkg_x / 2.0
    xChamferFab = xLeftFab + chamfer
    xPadLeft = xCenter - pitchX * ((config.layout_x - 1) / 2.0)
    xLeftCrtYd = crt_round(xCenter - (pkg_x / 2.0 + crtYdOffset))
    xRightCrtYd = crt_round(xCenter + (pkg_x / 2.0 + crtYdOffset))

    yCenter = 0.0
    yTopFab = yCenter - pkg_y / 2.0
    yBottomFab = yCenter + pkg_y / 2.0
    yChamferFab = yTopFab + chamfer
    yPadTop = yCenter - pitchY * ((config.layout_y - 1) / 2.0)
    yTopCrtYd = crt_round(yCenter - (pkg_y / 2.0 + crtYdOffset))
    yBottomCrtYd = crt_round(yCenter + (pkg_y / 2.0 + crtYdOffset))
    yRef = yTopFab - 1.0
    yValue = yBottomFab + 1.0

    wFab = GC.GLOBAL_CONFIG.fab_line_width
    wCrtYd = GC.GLOBAL_CONFIG.courtyard_line_width
    wSilkS = GC.GLOBAL_CONFIG.silk_line_width

    # silkOffset should comply with pad clearance as well
    yPadTopEdge = yPadTop - spec["pad_size"][1] / 2.0
    xPadLeftEdge = xPadLeft - spec["pad_size"][0] / 2.0

    xSilkOffset = max(
        GC.GLOBAL_CONFIG.silk_fab_offset,
        xLeftFab + GC.GLOBAL_CONFIG.silk_pad_offset - xPadLeftEdge,
    )
    ySilkOffset = max(
        GC.GLOBAL_CONFIG.silk_fab_offset,
        yTopFab + GC.GLOBAL_CONFIG.silk_pad_offset - yPadTopEdge,
    )

    silkSizeX = pkg_x + 2 * (xSilkOffset - GC.GLOBAL_CONFIG.silk_fab_offset)
    silkSizeY = pkg_y + 2 * (ySilkOffset - GC.GLOBAL_CONFIG.silk_fab_offset)

    silkChamfer = GC.GLOBAL_CONFIG.fab_bevel.get_chamfer_size(
        min(silkSizeX, silkSizeY)
    )

    xLeftSilk = xLeftFab - xSilkOffset
    xRightSilk = xRightFab + xSilkOffset
    xChamferSilk = xLeftSilk + silkChamfer
    yTopSilk = yTopFab - ySilkOffset
    yBottomSilk = yBottomFab + ySilkOffset
    yChamferSilk = yTopSilk + silkChamfer

    # Text
    f.append(
        Property(
            name=Property.REFERENCE,
            text="REF**",
            at=[xCenter, yRef],
            layer="F.SilkS",
            size=s1,
            thickness=t1,
        )
    )
    f.append(
        Property(
            name=Property.VALUE,
            text=config.name,
            at=[xCenter, yValue],
            layer="F.Fab",
            size=s1,
            thickness=t1,
        )
    )
    f.append(
        Text(
            text="${REFERENCE}",
            at=[xCenter, yCenter],
            layer="F.Fab",
            size=s2,
            thickness=t2,
            rotation=f_fab_ref_rot,
        )
    )

    # Fab
    f.append(
        PolygonLine(
            shape=[
                [xRightFab, yBottomFab],
                [xLeftFab, yBottomFab],
                [xLeftFab, yChamferFab],
                [xChamferFab, yTopFab],
                [xRightFab, yTopFab],
                [xRightFab, yBottomFab],
            ],
            layer="F.Fab",
            width=wFab,
        )
    )

    # Courtyard
    f.append(
        Rectangle(
            start=[xLeftCrtYd, yTopCrtYd],
            end=[xRightCrtYd, yBottomCrtYd],
            layer="F.CrtYd",
            width=wCrtYd,
        )
    )

    # Silk

    arrow_apex = Vector2D(xLeftSilk, yTopSilk)
    min_arrow_size = wSilkS * 3
    arrow_size = max(min_arrow_size, crtYdOffset / 2)

    f.append(
        pin1_arrow.Pin1SilkScreenArrow45Deg(
            arrow_apex, Direction.SOUTHEAST, arrow_size, "F.SilkS", wSilkS
        )
    )

    f.append(
        PolygonLine(
            shape=[
                [xChamferSilk, yTopSilk],
                [xRightSilk, yTopSilk],
                [xRightSilk, yBottomSilk],
                [xLeftSilk, yBottomSilk],
                [xLeftSilk, yChamferSilk],
            ],
            layer="F.SilkS",
            width=wSilkS,
        )
    )

    # Pads
    for layout_data in config.layout_data_list:
        _make_pad_grid(
            f, layout_data, config, x_center=xCenter, y_center=yCenter
        )

    dwg_nodes = fp_additional_drawing.create_additional_drawings(  # pyright: ignore
        config.additional_drawings, GC.GLOBAL_CONFIG, fp_evaluator
    )
    f.extend(dwg_nodes)

    if staggered:
        pdesc = str(spec.get("pitch")) if "pitch" in spec else f"{pitchX}x{pitchY}"
        sdesc = f"{staggered.upper()}-staggered "
    else:
        pdesc = str(pitchX) if pitchX == pitchY else f"{pitchX}x{pitchY}"
        sdesc = ""

    description_parts = [
        config.metadata.description if config.metadata.description else "",
        f"{pkg_x}x{pkg_y}mm",
        f"{config.num_balls} Ball",
        f"{sdesc}{config.layout_x}x{config.layout_y} Layout",
        f"{pdesc}mm Pitch",
        f"generated with kicad-footprint-generator ipc_bga_generator.py",  # For zero-diff. Replace with generator_name later.
    ]

    if config.metadata.datasheet:
        description_parts.append(config.metadata.datasheet)

    f.description = ", ".join(description_parts)

    f.tags = [config.package_type, str(config.num_balls), pdesc]
    f.tags += config.metadata.compatible_mpns
    f.tags += config.metadata.additional_tags

    # #################### Output and 3d model ############################
    f.add_standard_3d_model_to_footprint(config.lib_name, config.name)
    write_footprint(f, config.lib_name, generator_name)

def _make_pad_grid(
    f: Footprint,
    layout_info: LayoutData,
    config: GridArraySpec,
    x_center: float = 0.0,
    y_center: float = 0.0,
) -> None:
    layout_dict = layout_info.layout_dict
    spec = config.spec
    pad_data_list = layout_info.pad_data_list

    pad_shape = layout_dict.get("pad_shape", spec.get("pad_shape", "circle"))
    paste_shape = layout_dict.get("paste_shape", spec.get("paste_shape"))

    if paste_shape and paste_shape != pad_shape:
        layers = ["F.Cu", "F.Mask"]
    else:
        layers = Pad.LAYERS_SMT

    ref_pad = Pad(
        number=pad_data_list[0].name,
        type=Pad.TYPE_SMT,
        fab_property=Pad.FabProperty.BGA,
        shape=pad_shape,
        at=pad_data_list[0].position,
        size=layout_dict.get("pad_size") or spec["pad_size"],
        layers=layers,
        radius_ratio=GC.GLOBAL_CONFIG.roundrect_radius_handler,  # type: ignore
    )
    f.append(ref_pad)

    ref_paste_pad: Pad | None = None

    if paste_shape and paste_shape != pad_shape:
        # Footgun warning: When pcbnew renders a paste-only pad like this, it actually
        # ignores all paste `margin settings both of the pad and of the footprint, and
        # creates a stencil opening of exactly the size of the pad. Thus, we have to
        # pre-compute paste margin here. Note that KiCad implements paste margin with an
        # actual geometric offset, i.e. yielding a rounded rect for square pads. Thus,
        # we have to implement similar offsetting logic here to stay consistent.

        pasteMargin = layout_dict.get("paste_margin", spec.get("paste_margin", 0))
        size = list(layout_dict.get("pad_size") or spec["pad_size"])
        corner_ratio = GC.GLOBAL_CONFIG.roundrect_radius_handler.radius_ratio

        if paste_shape == "circle":
            size[0] += 2 * pasteMargin
            size[1] += 2 * pasteMargin

        elif paste_shape == "rect":
            if pasteMargin <= 0:
                size[0] += 2 * pasteMargin
                size[1] += 2 * pasteMargin

            else:
                corner_ratio = pasteMargin / min(size)
                size[0] += 2 * pasteMargin
                size[1] += 2 * pasteMargin
                paste_shape = "roundrect"

        elif paste_shape == "roundrect":
            corner_radius = min(size) * corner_ratio
            size[0] += 2 * pasteMargin
            size[1] += 2 * pasteMargin
            corner_radius += pasteMargin

            if corner_radius < 0:
                paste_shape = "rect"
            else:
                corner_ratio = corner_radius / min(size)

        paste_radius_handler = RoundRadiusHandler(
            radius_ratio=corner_ratio,
        )

        ref_paste_pad = Pad(
            number="",
            type=Pad.TYPE_SMT,
            shape=paste_shape,
            at=pad_data_list[0].position,
            size=size,  # type: ignore
            layers=["F.Paste"],
            round_radius_handler=paste_radius_handler,
        )
        f.append(ref_paste_pad)

    for i in range(1, len(pad_data_list)):
        f.append(
            ReferencedPad(
                reference_pad=ref_pad,
                number=pad_data_list[i].name,
                at=pad_data_list[i].position,
            )
        )
        if ref_paste_pad is not None:
            f.append(
                ReferencedPad(
                    reference_pad=ref_paste_pad,
                    number="",
                    at=pad_data_list[i].position,
                )
            )
