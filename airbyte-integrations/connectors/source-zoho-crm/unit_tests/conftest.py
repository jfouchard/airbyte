#
# Copyright (c) 2021 Airbyte, Inc., all rights reserved.
#

from unittest.mock import Mock

import pytest
import requests


@pytest.fixture
def request_mocker():
    def factory(status=200, content=""):
        response = requests.Response()
        response.status_code = status
        response._content = content
        request_mock = Mock(return_value=response)
        return request_mock

    return factory