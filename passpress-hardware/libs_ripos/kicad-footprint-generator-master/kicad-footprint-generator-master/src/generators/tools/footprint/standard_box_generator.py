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

import sys

from collections.abc import Generator
from typing import Any

from KicadModTree import Footprint, FootprintType, ModArgparser, Pad, Model, Node, Text
from kilibs.geom import Direction, Vector2D
from generators.tools.footprint.drawing_tools_silk import SilkArrowSize
from kilibs.config import global_config
from generators.tools.footprint.declarative_def_tools import common_metadata
from generators.tools.footprint.save_footprint import write_footprint

from generators.tools.footprint.nodes.layouts.n_pad_box_layout import NPadBoxLayout
from generators.tools.footprint.nodes.layouts.footprint_layout import SilkStyle


def make_pad_from_data(
    global_config: global_config.GlobalConfig, n: list[str | float]
) -> Pad:
    """
    Convert a list of pad data into a Pad object.

    Data format:
    [pad_type, number, x, y, width, height, drill]

    e.g. ['tht', '1', 0.0, 0.0, 1.0, 1.0, 0.5]
    """

    pad_type = str(n[0])
    number = str(n[1])
    dh = n[6]

    at = Vector2D.from_floats(float(n[2]), -float(n[3]))

    if float(n[5]) == 0.0:
        size = Vector2D.from_floats(float(n[4]), float(n[4]))  # Square/round pad
    else:
        size = Vector2D.from_floats(float(n[4]), float(n[5]))

    if pad_type == "smd":
        shape = Pad.SHAPE_ROUNDRECT
        pad_type = Pad.TYPE_SMT
        layers = Pad.LAYERS_SMT
    elif pad_type in ["tht", "thtr"]:
        shape = Pad.SHAPE_ROUNDRECT if pad_type == "thtr" else Pad.SHAPE_OVAL
        pad_type = Pad.TYPE_THT
        layers = Pad.LAYERS_THT
    elif pad_type == "npth":
        shape = Pad.SHAPE_OVAL
        pad_type = Pad.TYPE_NPTH
        layers = Pad.LAYERS_NPTH
    else:
        raise ValueError(f"Unknown pad type: {pad_type}")

    return Pad(
        drill=dh,
        at=at,
        size=size,
        layers=layers,
        number=number,
        type=pad_type,
        shape=shape,
        round_radius_handler=global_config.roundrect_radius_handler,
    )


def build_extra_texts(text_data: list[list[str | float]]) -> list[Node]:
    """
    Convert a list of text data into a list of Nodes (probably, but not
    necessarily, Text nodes).

    Data format: [x, y, text, layer, size_x, size_y]
    """

    nodes: list[Node] = []

    for n in text_data:
        at = Vector2D.from_floats(float(n[0]), -float(n[1]))
        stss = str(n[2])

        if len(n) > 3:
            layer = str(n[3])
        else:
            layer = "F.SilkS"

        if len(n) > 5:
            size = Vector2D.from_floats(float(n[4]), float(n[5]))
        else:
            size = Vector2D.from_floats(1.0, 1.0)

        new_node = Text(text=stss, at=at, layer=layer, size=size)

        nodes.append(new_node)

    return nodes


def arrow_nesw_from_str(s: str | None) -> Direction | None:
    """
    Convert a string representing an arrow direction to a cardinal direction.
    """

    if not s:  # None or empty string
        return None

    match s.upper():
        case "NORTH":
            return Direction.NORTH
        case "SOUTH":
            return Direction.SOUTH
        case "EAST":
            return Direction.EAST
        case "WEST":
            return Direction.WEST
        case _:
            pass

    raise ValueError(f"Invalid arrow direction: {s}. Expected one of: NORTH, SOUTH, EAST, WEST.")


def converter(generator_name: str, args: dict[str, Any]) -> int:

    metadata = common_metadata.CommonMetadata(args)
    footprint_name = args["name"]
    fptag = args["tags"]
    SmdTht = str(args["smd_tht"])
    at = Vector2D(args["at"])
    size = Vector2D(args["size"])
    pins = args["pins"]
    extratexts = args["extratexts"]
    body_position_tolerance = Vector2D(args["body_position_tolerance"])
    body_size_tolerance = Vector2D(args["body_size_tolerance"])
    courtyard = float(args["courtyard"])
    automatic_pin1_mark = bool(args["automatic_pin1_mark"])

    arrow_points = arrow_nesw_from_str(args["arrow_points"])

    # Until this can be passed in properly, use the default global config
    global_cfg = global_config.GLOBAL_CONFIG

    # This a very naive way of calculating the total tolerance referenced to the
    # origin, but it's a start
    total_body_tolerance = body_position_tolerance + body_size_tolerance

    # Clearance from the _nominal_ body to the courtyard _centreline_
    courtyard_clearance = total_body_tolerance + Vector2D(courtyard, courtyard)
    # Clearance from the _nominal_ body to the _inside_ of the silk line
    fab_to_silk_clearance = total_body_tolerance

    if not metadata.library_name:
        raise ValueError("Library name is required in the metadata.")

    if not metadata.description:
        raise ValueError("Description is required in the metadata.")

    dir3D = metadata.library_name + '.3dshapes'

    footprint_type = FootprintType.SMD if SmdTht == "smd" else FootprintType.THT
    f = Footprint(footprint_name, footprint_type)

    model_filename = global_cfg.model_3d_prefix + dir3D + "/" + footprint_name + global_cfg.model_3d_suffix

    words = footprint_name.split("_")

    if words[-1].lower().startswith('handsolder'):
        words[-1] = ''
        ff = '_'.join(words)
        model_filename = global_cfg.model_3d_prefix + dir3D + "/" + ff + global_cfg.model_3d_suffix

    descr: list[str] = [
        metadata.description,
    ]
    if metadata.datasheet:
        descr.append(metadata.datasheet)

    descr.append(global_cfg.get_generated_by_description("StandardBox_generator.py"))  # For zero-diff. Replace with generator_name later.

    f.description = ", ".join(descr)

    f.tags = fptag.split() + metadata.compatible_mpns

    def pad_factory() -> Generator[Pad, Any, None]:
        """A factory function that returns an iterator of pads, using the "standard box"
        pad data format provided in the `pins` argument."""
        for pin in pins:
            pad = make_pad_from_data(global_cfg, pin)
            yield pad

    offset = size / 2 + Vector2D(at.x, -at.y)

    min_body_size = size.min_val

    # Basic heuristic to determine the silk arrow size based on the body size
    if min_body_size < 4:
        silk_arrow_size = SilkArrowSize.MEDIUM
    elif min_body_size < 10:
        silk_arrow_size = SilkArrowSize.LARGE
    else:
        silk_arrow_size = SilkArrowSize.HUGE

    layout = NPadBoxLayout(
        global_config=global_cfg,
        body_size=size,
        body_offset=offset,
        pad_factory=pad_factory,
        silk_style=SilkStyle.TIGHT,
        is_polarized=automatic_pin1_mark,
        footprint_name=f.name,
        body_to_courtyard_clearance=courtyard_clearance,
        additional_silk_clearance=fab_to_silk_clearance,
        silk_arrow_direction_if_inside=arrow_points,
        silk_arrow_size=silk_arrow_size,
    )

    f += layout

    f += build_extra_texts(extratexts)

    f += Model(filename=model_filename)

    write_footprint(f, metadata.library_name, generator_name)
    return 1


def main(generator_name: str, args: list[str] | None = None) -> int:
    ipc_default_courtyard_clearance = 0.25

    # parse arguments using optparse or argparse or what have you
    parser = ModArgparser(converter)
    # the root node of .yml files is parsed as name
    parser.add_parameter("library_name", type=str, required=True)
    parser.add_parameter("name", type=str, required=True)
    parser.add_parameter("description", type=str, required=True)
    parser.add_parameter("datasheet", type=str, required=True)
    parser.add_parameter("tags", type=str, required=True)
    parser.add_parameter("compatible_mpns", type=list, required=False)
    parser.add_parameter("smd_tht", type=str, required=False, default='tht')
    parser.add_parameter("at", type=list, required=True)
    parser.add_parameter("size", type=list, required=False)
    parser.add_parameter("pins", type=list, required=True)
    # Maximum body position deviation in each axis
    parser.add_parameter("body_position_tolerance", type=list, required=False, default=[0, 0])
    # Maximum body size deviation in each axis from the given nominal size
    parser.add_parameter("body_size_tolerance", type=list, required=False, default=[0, 0])
    # Courtyard outside the maximum body extent (i.e. not including the body tolerance)
    parser.add_parameter("courtyard", type=float, required=False, default=ipc_default_courtyard_clearance)
    parser.add_parameter("extratexts", type=list, required=False, default=[])
    parser.add_parameter("automatic_pin1_mark", type=bool, required=False, default=True)
    parser.add_parameter("arrow_points", type=str, required=False)

    # now run our script which handles the whole part of parsing the files
    return parser.run(generator_name, args)

if __name__ == '__main__':
    import sys
    main(sys.argv[1:])
