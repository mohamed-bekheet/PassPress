#!/usr/bin/python
# -*- coding: utf-8 -*-
#
# CadQuery script for generating tactile button 3D models
#
# This is derived from a CadQuery script for generating QFP models in
# X3D format, from https://bitbucket.org/hyOzd/freecad-macros
# author hyOzd
#
# Thanks to Frank Severinsen (Shack) for including the orignal VRML
# materials.
#
# Copyright (c) 2015 Maurice https://launchpad.net/~easyw
# Copyright (c) 2021 jmwright (https://github.com/jmwright)
# Work sponsored by KiCAD Services Corporation
#      (https://www.kipro-pcb.com/)
#
# Copyright (c) 2024-2025 Martin Sotirov <martin@libtec.org>
#
# All trademarks within this guide belong to their legitimate owners.
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU Lesser General Public License (LGPL)
# as published by the Free Software Foundation; either version 2 of
# the License, or (at your option) any later version.
# for detail see the LICENCE text file.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Library General Public License for more details.
#
# You should have received a copy of the GNU Library General Public
# License along with this program; if not, write to the Free Software
# Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA

import logging

import cadquery as cq

from generators.tools.model import export_tools
from generators.tools.spec.legacy_model_spec import LegacyModelSpec


def create_models(spec: LegacyModelSpec, generator_name: str) -> int:
    """Create the 3D models.

    Args:
        spec: The spec of the part(s) to generate.
        generator_name: The name of the generator.

    Returns:
        The number of models generated.
    """
    # Generate the current model
    if spec.spec["model_class"] == "tactile":
        from .cq_models import cq_tactile as cqm
    else:
        logging.error("No match found for the model_class.")
        return 0

    body = cqm.make_body(spec.spec)
    shell = cqm.make_shell(spec.spec)
    pins = cqm.make_pins(spec.spec)
    actuator = cqm.make_actuator(spec.spec)
    actuator_base = cqm.make_actuator_base(spec.spec)

    if spec.spec.get("rotation"):
        body = body.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
        shell = shell.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
        pins = pins.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
        actuator = actuator.rotate((0, 0, 0), (0, 0, 1), spec.spec["rotation"])
        if actuator_base:
            actuator_base = actuator_base.rotate(
                (0, 0, 0), (0, 0, 1), spec.spec["rotation"]
            )

    if spec.spec.get("translation"):
        body = body.translate(spec.spec["translation"])
        shell = shell.translate(spec.spec["translation"])
        pins = pins.translate(spec.spec["translation"])
        actuator = actuator.translate(spec.spec["translation"])
        if actuator_base:
            actuator_base = actuator_base.translate(spec.spec["translation"])

    parts: list[cq.Workplane] = [body, shell, pins, actuator]
    color_names: list[str] = [
        spec.spec["body_color_key"],
        spec.spec["shell_color_key"],
        spec.spec["pins_color_key"],
        spec.spec["actuator_color_key"],
    ]
    if actuator_base:
        parts.append(actuator_base)
        color_names.append(spec.spec["actuator_base_color_key"])

    export_tools.export(
        generator_name=generator_name,
        lib_name=spec.spec["destination_dir"],
        model_name=spec.id,
        parts=parts,
        color_names=color_names,
    )
    return 1
