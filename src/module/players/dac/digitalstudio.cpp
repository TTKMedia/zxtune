/**
* 
* @file
*
* @brief  DigitalStudio chiptune factory implementation
*
* @author vitamin.caig@gmail.com
*
**/

//local includes
#include "digitalstudio.h"
#include "dac_simple.h"
//common includes
#include <make_ptr.h>
//library includes
#include <formats/chiptune/digital/digitalstudio.h>

namespace Module
{
namespace DigitalStudio
{
  const std::size_t CHANNELS_COUNT = 3;

  typedef DAC::SimpleModuleData ModuleData;
  typedef DAC::SimpleDataBuilder DataBuilder;

  class Factory : public DAC::Factory
  {
  public:
    virtual DAC::Chiptune::Ptr CreateChiptune(const Binary::Container& rawData, Parameters::Container::Ptr properties) const
    {
      DAC::PropertiesHelper props(*properties);
      const DataBuilder::Ptr dataBuilder = DAC::CreateSimpleDataBuilder<CHANNELS_COUNT>(props);
      if (const Formats::Chiptune::Container::Ptr container = Formats::Chiptune::DigitalStudio::Parse(rawData, *dataBuilder))
      {
        props.SetSource(*container);
        return DAC::CreateSimpleChiptune(dataBuilder->GetResult(), properties, CHANNELS_COUNT);
      }
      else
      {
        return DAC::Chiptune::Ptr();
      }
    }
  };
  
  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}
}