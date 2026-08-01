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

import math
from typing import Any, Literal, NamedTuple
import itertools

from kilibs.geom import Vector2D
from generators.tools.footprint.declarative_def_tools import (
    common_metadata,
    fp_additional_drawing,
    rule_area_properties,
)

from generators.tools.spec.spec_registry import register_spec
from ..package_spec import PackageSpec

from ..config import PACKAGE_CONFIG
from .config import ROW_NAMES


class PadData(NamedTuple):
    name: str
    position: Vector2D


class LayoutData(NamedTuple):
    layout_dict: dict[str, Any]
    pad_data_list: list[PadData]


@register_spec
class GridArraySpec(PackageSpec):
    """
    A type that represents the configuration of a grid array footprint
    (probably from a YAML config block).

    Over time, add more type-safe accessors to this class, and replace
    use of the raw dictionary.
    """

    def __init__(
        self,
        id: str = "",
        spec: dict[str, Any] = {},
        file_name: str = "",
    ) -> None:
        """Create an instance of `PackageSpec`.

        Args:
            id: The name/identifier of the spec. Typically, this is the name of the key
                of the spec (in the YAML file) or the name of the component.
            spec: The dictionary containing the specification of the component.
            file_name: The name of the YAML file that holds this spec definition.
        """
        # Instance attributes for generator independent data:
        self.additional_drawings: list[fp_additional_drawing.FPAdditionalDrawing]
        """The list containing additional drawings."""
        self.rule_areas: list[rule_area_properties.RuleAreaProperties] = []
        """The rule areas (zones)."""

        # Instance attributes for genreator specific data:
        self.marker: str | None
        """The pad above which the first pin marker is placed"""

        # Instance attributes for pad details:
        self.layout_data_list: list[LayoutData]

        # Instance attributes related to the dimensions of the package:
        self.body_size_x: float
        """Size of the package body in x direction."""
        self.body_size_y: float
        """Size of the package body in y direction."""

        # Instance attributes for the pinning data:
        self.num_balls: int
        """Number of balls in the package."""
        self.layout_x: int
        """Number of balls in x direction."""
        self.layout_y: int
        """Number of balls in y direction."""

        # Instance attributes for the 3D model:
        self.body_pcb_gap: float
        """Size of the gap between the PCB and the package body."""
        self.body_height: float
        """Size of the package body in z direction."""
        self.overall_height: float
        """Overall height of the package (sum of `body_pcb_gap` and `body_height`)."""
        self.body_fillet: float
        """Size of the body fillet in mm."""
        self.first_corner_chamfer: float
        """Size of the chamfer of the first pin corner."""
        self.corner_chamfer: float
        """Size of the chamfer of the other corners."""
        self.molded: bool
        """Whether the package has an overmolded part."""
        self.mold_size_x: float
        """Size of the over molded part in x direction."""
        self.mold_size_y: float
        """Size of the over molded part in y direction."""
        self.mold_size_z_bottom: float
        """Size of the bottom part of the package in z direction."""
        self.ball_diameter: float | None
        """Ball diameter."""
        self.seating_plane: float
        """Height of the seating plane."""

        # Instance attributes related to the names:
        self.device_type: str
        """Device type (WLCSP, dsBGA, ...)"""
        self.package_type: str
        """Package type (BGA, CSP, LGA, ...)."""
        self.name: str
        """Name of the FP and 3D model."""
        self.lib_name: str
        """Name of the library."""

        super().__init__(id, spec, file_name)

        if file_name.endswith("cq_parameters_obsolete.yaml"):
            self.has_fp_data = False
        else:
            self.has_fp_data = True

        self.marker = spec.get("marker")
        self.layout_data_list = []

        self._extract_generator_independent_data()
        self._extract_dimension_data()
        self._extract_pinning_data()
        self._extract_3d_data()
        self._compose_device_name()
        self._compose_lib_name()

    def _extract_generator_independent_data(self) -> None:
        self.metadata = common_metadata.CommonMetadata(self.spec)

        self.additional_drawings = (
            fp_additional_drawing.FPAdditionalDrawing.from_standard_yaml(self.spec)  # type: ignore
        )
        self.rule_areas = rule_area_properties.RuleAreaProperties.from_standard_yaml(  # pyright: ignore
            self.spec
        )

    def _extract_dimension_data(self) -> None:
        self.body_size_x = self.spec["body_size_x"]
        self.body_size_y = self.spec["body_size_y"]

    def _extract_pinning_data(self) -> None:
        if "pitch" in self.spec:
            self.pitch = Vector2D(self.spec["pitch"], self.spec["pitch"])
        elif "pitch_x" in self.spec and "pitch_y" in self.spec:
            self.pitch = Vector2D(self.spec["pitch_x"], self.spec["pitch_y"])
        else:
            raise KeyError("Either pitch or both pitch_x and pitch_y must be given.")

        self.layout_x = self.spec["layout_x"]
        self.layout_y = self.spec["layout_y"]

        # To facilitate iteration through the layouts create a list with main
        # and sublayouts
        layouts = [self.spec] + self.spec.get("secondary_layouts", [])
        self.num_balls = 0
        for layout in layouts:
            self.num_balls += self._calculate_pad_names_and_positions_in_layout(layout)

    def _extract_3d_data(self) -> None:
        self.has_3d_data = True
        if "body_pcb_gap" in self.spec and "overall_height" in self.spec:
            self.body_pcb_gap = self.spec["body_pcb_gap"]
            self.overall_height = self.spec["overall_height"]
            self.body_height = self.overall_height - self.body_pcb_gap
        elif "body_height" in self.spec and "overall_height" in self.spec:
            self.body_height = self.spec["body_height"]
            self.overall_height = self.spec["overall_height"]
            self.body_pcb_gap = self.overall_height - self.body_height
        elif "body_height" in self.spec and "body_pcb_gap" in self.spec:
            self.body_height = self.spec["body_height"]
            self.body_pcb_gap = self.spec["body_pcb_gap"]
            self.overall_height = self.body_pcb_gap + self.body_height
        else:
            self.has_3d_data = False
            self.body_pcb_gap = self.spec.get("body_pcb_gap", 0.0)
            self.body_height = self.spec.get("body_height", 0.0)
            self.overall_height = self.spec.get("overall_height", 0.0)

        self.body_fillet = self.spec.get("body_fillet", 0.0)
        self.first_corner_chamfer = self.spec.get("first_corner_chamfer", 0.25)
        self.corner_chamfer = self.spec.get("corner_chamfer", 0.25)
        self.molded = self.spec.get("molded", False)
        self.mold_size_x = self.spec.get("mold_size_x", self.body_size_x * (1 - 0.065))
        self.mold_size_y = self.spec.get("mold_size_y", self.body_size_y * (1 - 0.065))
        self.mold_size_z_bottom = self.spec.get("mold_size_z_bottom", 0.0)
        self.ball_diameter = self.spec.get("ball_diameter")
        self.seating_plane = self.spec.get("seating_plane", 0.0)

    def calculate_stagger(
        self, layout_def: dict[str, Any] | None = None
    ) -> tuple[float, float, Literal["x", "y"] | None]:
        if layout_def is None:
            layout_def = self.spec
        staggered = layout_def.get("staggered", "").lower() or None
        pitch = layout_def.get("pitch")
        pitch_x = layout_def.get("pitch_x")
        pitch_y = layout_def.get("pitch_y")

        if staggered not in [None, "x", "y"]:
            raise ValueError('staggered must be either "x" or "y"')

        if staggered and pitch:
            height = pitch * math.sin(math.radians(60))
            if staggered == "x":
                pitch_x = pitch_x or pitch / 2
                pitch_y = pitch_y or height
            elif staggered == "y":
                pitch_x = pitch_x or height
                pitch_y = pitch_y or pitch / 2
        else:
            pitch_x = pitch_x or pitch
            pitch_y = pitch_y or pitch

        if not (pitch_x and pitch_y):
            raise KeyError("Either pitch or both pitch_x and pitch_y must be given.")

        return pitch_x, pitch_y, staggered

    def _calculate_pad_names_and_positions_in_layout(
        self, layout_dict: dict[str, Any], x_center: float = 0.0, y_center: float = 0.0
    ) -> int:
        pad_data_list: list[PadData] = []
        layout_x: int = layout_dict["layout_x"]
        layout_y: int = layout_dict["layout_y"]
        row_names = layout_dict.get(
            "row_names", self.spec.get("row_names", ROW_NAMES)
        )
        if row_prefix := layout_dict.get("row_name_prefix"):
            row_names = [str(row_prefix) + n for n in row_names]
        if (first_row := layout_dict.get("first_row")) is not None:
            row_names = row_names[row_names.index(first_row) :]
        row_names = row_names[:layout_y]
        first_col = layout_dict.get("first_column", 1)
        row_skips = layout_dict.get("row_skips", [])
        area_skips = layout_dict.get("area_skips", [])
        pad_skips = {skip.upper() for skip in layout_dict.get("pad_skips", [])}
        pitch_x, pitch_y, staggered = self.calculate_stagger(layout_dict)

        for row_start, col_start, row_end, col_end in area_skips:
            rows = row_names[
                row_names.index(row_start.upper()) : row_names.index(row_end.upper())
                + 1
            ]
            cols = range(col_start, col_end + 1)
            pad_skips |= {f"{a}{b}" for a, b in itertools.product(rows, cols)}

        for row, skips in zip(row_names, row_skips):
            for skip in skips:
                if isinstance(skip, int):
                    pad_skips.add(f"{row}{skip}")
                else:
                    pad_skips |= {f"{row}{skip}" for skip in range(*skip)}

        if first_ball := layout_dict.get("first_ball"):
            if not staggered:
                raise ValueError("first_ball only makes sense for staggered layouts.")

            if first_ball not in ("A1", "B1", "A2"):
                raise ValueError('first_ball must be "A1" or "A2".')

        if staggered:
            if not first_ball:
                first_ball = "A1"

            skip_even = first_ball == "A1"

            for row_num, row in enumerate(row_names, start=1):
                for col in range(first_col, first_col + layout_x):
                    is_even = (row_num + col - first_col) % 2 == 0
                    if is_even == skip_even:
                        pad_skips.add(f"{row}{col}")

        offset_x = layout_dict.get("offset_x", 0.0)
        offset_y = layout_dict.get("offset_y", 0.0)
        x_pad_left = x_center - pitch_x * ((layout_x - 1) / 2.0) + offset_x
        y_pad_top = y_center - pitch_y * ((layout_y - 1) / 2.0) + offset_y

        for rowNum, row in enumerate(row_names):
            rowSet = {
                col
                for col in range(first_col, layout_x + first_col)
                if f"{row}{col}" not in pad_skips
            }
            for col in rowSet:
                pad_data_list.append(
                    PadData(
                        name=f"{row}{col}",
                        position=Vector2D(
                            x_pad_left + (col - first_col) * pitch_x,
                            y_pad_top + rowNum * pitch_y,
                        ),
                    )
                )

        self.layout_data_list.append(
            LayoutData(layout_dict=layout_dict, pad_data_list=pad_data_list)
        )
        return layout_x * layout_y - len(pad_skips)

    def _compose_device_name(self) -> None:
        self.package_type = self.spec.get("package_type", "BGA")
        self.device_type = self.spec.get("device_type", self.spec.get("package_type", ""))

        if not self.has_fp_data:  # for 3d models defined in cq_parameters.yaml
            self.name = self.id
            return
        if "name" in self.spec:
            self.name = self.spec["name"]
            return
        elif self.spec.get("name_equal_to_key"):
            self.name = self.id
            return

        # Compute number of balls + diverse suffix strings
        pitch_text = ""
        stagger_text = ""
        offcenter_text = ""
        for layout_info in self.layout_data_list:
            layout = layout_info.layout_dict
            if pitch := layout.get("pitch"):
                new_pitch_text = f"P{pitch}mm"
            else:
                pitch_x = layout.get("pitch_x")
                pitch_y = layout.get("pitch_y")
                if not (pitch_x and pitch_y):
                    raise KeyError(
                        "Either pitch or both pitch_x and pitch_y must " "be given."
                    )
                new_pitch_text = f"P{pitch_x}x{pitch_y}mm"
            if not pitch_text.endswith(new_pitch_text):
                pitch_text += new_pitch_text
            if "staggered" in layout:
                stagger_text = "_Stagger"
            if "offset_x" in layout or "offset_y" in layout:
                offcenter_text = "_Offcenter"

        if self.metadata.custom_name_format:
            name_format = self.metadata.custom_name_format
        else:
            name_format = PACKAGE_CONFIG["fp_name_bga_format_string_no_trailing_zero"]

        pad_suffix = ""
        if (
            self.spec.get("include_pad_diameter_in_name")
            and "pad_diameter" in self.spec
        ):
            pad_diameter = self.spec["pad_diameter"]
            pad_suffix = f"_Pad{pad_diameter}mm"

        ball_suffix = ""
        if (
            self.spec.get("include_ball_diameter_in_name")
            and "ball_diameter" in self.spec
        ):
            ball_diameter = self.spec["ball_diameter"]
            ball_suffix = f"_Ball{ball_diameter}mm"

        suffix = self.spec.get("suffix", "")

        self.name = (
            name_format.format(
                man=self.metadata.manufacturer or "",
                mpn=self.metadata.part_number or "",
                pkg=self.device_type,
                pincount=self.num_balls,
                size_x=self.body_size_x,
                size_y=self.body_size_y,
                nx=self.layout_x,
                ny=self.layout_y,
                pitch=pitch_text,
                ball_d=ball_suffix,
                pad_d=pad_suffix,
                stagger=stagger_text,
                offcenter=offcenter_text,
                suffix=suffix,
                suffix2="",
            )
            .replace("__", "_")
            .lstrip("_")
        )

    def _compose_lib_name(self) -> None:
        self.lib_name = f"Package_{self.package_type}"
