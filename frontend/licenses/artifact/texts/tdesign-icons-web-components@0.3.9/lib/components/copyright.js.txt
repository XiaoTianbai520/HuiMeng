'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var omi_module = require('../_chunks/dep-36eb48b6.js');
var icon = require('../icon.js');
require('../util/classname.js');
require('../util/size-props.js');
require('../util/check-url-and-load.js');

var _class;
function ownKeys(e, r) { var t = Object.keys(e); if (Object.getOwnPropertySymbols) { var o = Object.getOwnPropertySymbols(e); r && (o = o.filter(function (r) { return Object.getOwnPropertyDescriptor(e, r).enumerable; })), t.push.apply(t, o); } return t; }
function _objectSpread(e) { for (var r = 1; r < arguments.length; r++) { var t = null != arguments[r] ? arguments[r] : {}; r % 2 ? ownKeys(Object(t), !0).forEach(function (r) { omi_module._defineProperty(e, r, t[r]); }) : Object.getOwnPropertyDescriptors ? Object.defineProperties(e, Object.getOwnPropertyDescriptors(t)) : ownKeys(Object(t)).forEach(function (r) { Object.defineProperty(e, r, Object.getOwnPropertyDescriptor(t, r)); }); } return e; }
function _createSuper(t) { var r = _isNativeReflectConstruct(); return function () { var e, o = omi_module._getPrototypeOf(t); if (r) { var s = omi_module._getPrototypeOf(this).constructor; e = Reflect.construct(o, arguments, s); } else e = o.apply(this, arguments); return omi_module._possibleConstructorReturn(this, e); }; }
function _isNativeReflectConstruct() { try { var t = !Boolean.prototype.valueOf.call(Reflect.construct(Boolean, [], function () {})); } catch (t) {} return (_isNativeReflectConstruct = function _isNativeReflectConstruct() { return !!t; })(); }
var __defProp = Object.defineProperty;
var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
var __decorateClass = function __decorateClass(decorators, target, key, kind) {
  var result = kind > 1 ? void 0 : kind ? __getOwnPropDesc(target, key) : target;
  for (var i = decorators.length - 1, decorator; i >= 0; i--) if (decorator = decorators[i]) result = (kind ? decorator(target, key, result) : decorator(result)) || result;
  if (kind && result) __defProp(target, key, result);
  return result;
};
var element = {
  "attributes": {
    "fill": "none",
    "viewBox": "0 0 24 24",
    "width": "1em",
    "height": "1em"
  },
  "nodeName": "svg",
  "children": [{
    "attributes": {
      "id": "copyright"
    },
    "nodeName": "g",
    "children": [{
      "attributes": {
        "id": "fill1",
        "fill": "transparent",
        "d": "M2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12Z"
      },
      "nodeName": "path",
      "children": []
    }, {
      "attributes": {
        "id": "stroke1",
        "stroke": "currentColor",
        "d": "M2 12C2 6.47715 6.47715 2 12 2C17.5228 2 22 6.47715 22 12C22 17.5228 17.5228 22 12 22C6.47715 22 2 17.5228 2 12Z",
        "stroke-linecap": "square",
        "stroke-width": 2
      },
      "nodeName": "path",
      "children": []
    }, {
      "attributes": {
        "id": "stroke2",
        "stroke": "currentColor",
        "d": "M15.1816 15.1827C13.4243 16.9401 10.575 16.9401 8.81768 15.1827C7.06032 13.4254 7.06032 10.5761 8.81768 8.81875C10.575 7.06139 13.4243 7.06139 15.1816 8.81875",
        "stroke-linecap": "square",
        "stroke-width": 2
      },
      "nodeName": "path",
      "children": []
    }]
  }]
};
exports['default'] = (_class = /*#__PURE__*/function (_IconBase) {
  omi_module._inherits(CopyrightIcon, _IconBase);
  var _super = _createSuper(CopyrightIcon);
  function CopyrightIcon() {
    var _this;
    omi_module._classCallCheck(this, CopyrightIcon);
    _this = _super.call(this);
    _this.props = _objectSpread(_objectSpread({}, _this.props), {}, {
      id: "copyright"
    });
    return _this;
  }
  return omi_module._createClass(CopyrightIcon);
}(icon.IconBase), omi_module._defineProperty(_class, "icon", element), _class);
exports['default'] = __decorateClass([omi_module.Q("t-icon-copyright")], exports['default']);

exports.default = exports['default'];
//# sourceMappingURL=copyright.js.map
